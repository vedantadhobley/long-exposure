package com.longexposure.temporal.activities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.longexposure.analytics.Analytics;
import com.longexposure.llm.LlamaClient;
import com.longexposure.llm.SamplingParams;
import com.longexposure.narration.Catalog;
import com.longexposure.scoring.BreakdownFmt;
import com.longexposure.narration.InterpretationVerifier;
import com.longexposure.narration.TradeWindow;
import com.longexposure.storage.SchemaManager;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;

/**
 * Per-event INTERPRET implementation. See {@link InterpretEventActivity}
 * for the contract.
 *
 * <p>Per-event work:
 * <ol>
 *   <li>Load the event from {@code selected_events} (ts, ts_end, breakdown)
 *   <li>Compute pre/post ±60-sec trade-window summaries against {@code trades}
 *   <li>Build the prompt (system + user) with breakdown + catalog + window
 *   <li>One LLM call (RENDER preset)
 *   <li>Verify with {@link InterpretationVerifier}: numbers ⊆ breakdown ∪
 *       pre-window ∪ post-window; symbol present in prose; company name agrees
 *   <li>Upsert into {@code interpretations}, keyed by SHA256 of inputs
 * </ol>
 */
public final class InterpretEventActivityImpl implements InterpretEventActivity {

    private static final Logger LOG = LoggerFactory.getLogger(InterpretEventActivityImpl.class);

    private static final String MODEL_ID = System.getenv()
            .getOrDefault("LLAMA_MODEL", "Qwen3.5-122B-A10B");

    /** Bumped when the prompt changes; invalidates the cache. */
    /**
     * v11 (2026-05-28 evening, Phase 9-A) — inter-day INTERPRET now fires.
     * Catalog gained volume_deviation + time_in_book_drift entries.
     * InterpretEventActivityImpl branches on scorerId for these: skips the
     * ±60-sec window query (the day-level signal isn't temporally anchored),
     * skips computeDerived, and uses a different prompt section asking for
     * regime-interpretation framed by the catalog's documented drivers.
     *
     * <p>v10 added the pattern-name mislabel check; v9 added supporting
     * analytics (slippage, OFI, etc.).
     */
    private static final String PROMPT_VERSION = "interpret-v15-drivers-list-2026-05-31";

    /** Half-window for the surrounding trade context. */
    private static final long WINDOW_SECONDS = 60L;

    /** Max LLM attempts per event — re-roll on verifier failure (temp 0.7 gives variance). */
    private static final int MAX_LLM_ATTEMPTS = 3;

    private static final String SYSTEM_PROMPT = """
            You are a financial-data journalist writing one observation about what
            was happening on the IEX exchange around a single detected event. The
            observation identifies sequential / causal context — what happened
            immediately before the event, what happened immediately after, and
            how they relate.

            INPUTS:
              - Breakdown JSON for the event (the event's own measurements)
              - Pre-event and post-event trade-window summaries (same symbol,
                immediately before and after the event window)
              - Catalog entry for the event's scorer type (pattern-name vocabulary)

            OUTPUT: 1-2 sentences. Hard cap 350 chars. The event's symbol (ticker)
            appears literally in your prose — bare form ("on AMD") or parenthetical
            ("Advanced Micro Devices (AMD)"). Acronyms (LULD, NBBO, MCB, VWAP, HFT)
            glossed on first use. No preamble.

            GROUNDING — the primary rule:

            Every numeric token in your output must appear verbatim in the
            breakdown or in a window summary. The inputs are pre-formatted by the
            upstream pipeline: humanized durations ("48m 51s"), pre-computed
            ratios and ranges ("price_range_dollars", "orders_per_level"), and
            magnitude-appropriate units ("notional_million_dollars",
            "size_thousand_shares") all appear in the inputs as the values you
            should use. Do not compute, convert, approximate, or round — if the
            inputs carry 4,895, write 4,895, not "nearly 5,000" or "approximately
            4.9K"; if a window's total_shares is 1,114, write 1,114, not "over
            1,000". If the inputs do not carry a number you want to mention,
            do not mention it.

            REGISTER:

            Describe what the data shows. The catalog entry supplies pattern-name
            terminology — use it. Do not editorialize ("striking", "unusual"),
            claim intent ("the trader was X-ing", "this was spoofing"), or
            speculate about causes outside the wire data (news, off-exchange
            activity, FOMC, earnings).

            SUPPORTING ANALYTICS — the breakdown carries deeper measures that often
            sharpen the sequential / causal story you're telling. Use them when the
            context they describe is genuinely what the observation is about; do not
            list mechanically. Most useful for interpretation specifically:
              - pre_event_ofi: the book's directional lean BEFORE the event. Negative =
                sell-side accumulating; positive = buy-side. "OFI ran 0.42 negative in
                the seconds before — sell pressure was already building".
              - window_realized_vol_bps: surrounding-window volatility context.
                "realized vol ran N bps in the window, well above the day's baseline".
              - self_excitation (Hawkes branching): when > 0.6, "the burst cascaded —
                N% of orders triggered by prior arrivals" is a causal observation
                INTERPRET can make that DESCRIBE cannot.
              - post_event_reversion_pct + pre_to_post_vwap_move_bps (the derived
                block already attached to the prompt): "price reverted N% of the
                in-event move" / "VWAP moved N bps from pre to post". Use to
                distinguish transient impact from informed flow.
              - book_depth_imbalance: directional skew of the book at event-time
                ("the book was 3:1 skewed to the bid before the print").

            For the slice-fragile measures (window_vpin, window_kyle_lambda,
            window_jump_ratio), only mention if the narrative truly needs them, and
            always with the "on IEX" qualifier.

            FRAMING RULES — anchored renderings for fields that read awkwardly when
            rendered verbatim (these change wording, not grounding):

              - HALT TIMING. When present, the breakdown carries `halt_phase_span_label`
                — a COMPLETE GRAMMATICAL PHRASE describing WHEN the halt ran ("lasting
                through midday" / "starting in pre-market trading and resuming around
                midday"). Use it verbatim. Otherwise fall back to `halt_start_phase_label`
                + `halt_end_phase_label` joined with a connector verb (e.g. "began in
                pre-market and resumed around midday"), never with bare "to" ("began in
                pre-market to midday" is ungrammatical). NEVER render the
                `halt_start_et` / `halt_end_et` nanosecond-precision strings ("07:07:44.766",
                "14:35:02.554") — those are drill-down debug fields, not journalism.
                If you need a single anchor time, use the phase label.

              - `display_ratio_pct` (iceberg) is the ratio of displayed tip ÷ total
                executed size, in PERCENT. Render as "displayed only N% of total size"
                / "the visible tip was N% of executions" — NEVER as "display_ratio_pct
                of N" (field-name leak). Anchor the meaning ("hidden reserve",
                "institutional execution") with the catalog's documented drivers.

              - `drift_x` (time_in_book_drift) is the SYMMETRIC MAGNITUDE of how far
                today's average order lifetime moved from baseline (1.0 = no drift,
                > 1.0 = drift in either direction; the breakdown also carries a
                `direction` field for "shorter" or "longer"). Render as "N× shorter
                than typical" or "N× longer than typical", NEVER as "N units" or
                "N-point drift_x" (field-name leak).

              - `pre_event_ofi` near 0.0 means the book was balanced. Either say
                "the book was balanced" or omit the metric entirely. Do NOT render
                bare "pre_event_ofi 0.0" with the field name.

              - `robust_z` (volume_deviation, time_in_book_drift) is DIMENSIONLESS.
                NEVER render as "sigma" / "standard deviations" — the values run
                high because the underlying distributions are heavy-tailed. Render
                as "far above its typical range" / "well outside the trailing band",
                and let `percentile_rank` carry the intuition ("the most extreme day
                in the trailing two weeks").

            CANONICAL VOCABULARY (load-bearing — the same metric referenced
            different ways across narrations reads as different metrics):

              - Baselines: ALWAYS use "the trailing 2-week median" — works for any
                7-14 day baseline window regardless of the actual
                baseline_window_trading_days value. NEVER write a literal day count
                ("14-day", "10-day", "9-day") — the actual window varies by symbol
                and a specific number becomes a fabricated claim. NEVER "the
                average" / "normal" / "running mean" / bare "the baseline".
              - Multipliers: "22.2x the trailing median" (exact value, "x"
                suffix). NEVER "22 times" / "around 22x" / "more than 20x" /
                "approximately 22x".
              - Slippage: "7.4 basis points slippage" / "slipped 7.4 bps".
                NEVER "the price paid up by X bps" / "the sweep walked X bps".
              - Depth removal: "removed X% of displayed depth". NEVER "of the
                visible book" / "of available liquidity" / "of the order book".
              - Display ratio: "the displayed tip represented N% of total
                executed". NEVER "iceberg ratio" / "tip ratio" / "displayed
                proportion".
              - Depth from touch: "N basis points from the touch" /
                "N bps from the best price". NEVER bare "off the touch" /
                "from BBO".
              - Order-to-trade ratio: "no fills against N posted orders" when
                infinite (0 fills). NEVER "infinite order-to-trade ratio".

              These constraints govern PHRASING, not values. The numbers
              themselves stay verbatim from the breakdown.

            EMPTY-WINDOW CASE: when both surrounding windows are empty or quiet,
            state that explicitly — "the pattern appears in isolation" is a valid
            observation, not a failure to find context.

            EXAMPLES (illustrative shape):
              - "The layering on AMD at $429.50 preceded an 8,200-share post-event
                 execution at $431.00 — wire data shows aggressive consumption
                 immediately after the layering closed."
              - "The liquidity withdrawal on IWM opened a window where 47 trades
                 totaling 198,300 shares executed at a VWAP of $24.97 — depth
                 contraction did not stop incoming flow."
              - "No notable trading flanks this VTWO event (0 pre-event trades,
                 2 post-event trades for 200 shares); the pattern appears in
                 isolation."
            """;

    @Override
    public long interpret(final LocalDate tradingDate, final long selectedId) {
        ActivityExecutionContext actx = Activity.getExecutionContext();
        ObjectMapper json = new ObjectMapper();
        LlamaClient llama = LlamaClient.fromEnv();

        // Phase 7b: BackgroundHeartbeat daemon thread fires every 30 sec for
        // the activity's full duration, so even if the LLM call blocks longer
        // than the 1-min heartbeat-timeout (joi transient slow path), Temporal
        // sees liveness and doesn't kill the activity. Replaces the explicit-
        // checkpoint pattern (heartbeat-before / heartbeat-after the LLM call)
        // which couldn't cover the call's own duration.
        try (BackgroundHeartbeat hb = BackgroundHeartbeat.start(actx, "interpret-heartbeat", 30);
             Connection conn = openConnection()) {
            hb.setStage("schema:" + selectedId);
            SchemaManager.apply(conn);

            hb.setStage("load:" + selectedId);
            EventRow row = loadSelectedEvent(conn, tradingDate, selectedId, json);
            if (row == null) {
                LOG.warn("selected event not found  date={} selected_id={}", tradingDate, selectedId);
                return 0;
            }

            Catalog.Entry catalog = Catalog.forScorer(row.scorerId);
            if (catalog == null) {
                LOG.warn("no catalog entry for scorer — skipping  scorer={} selected_id={}",
                        row.scorerId, selectedId);
                return 0;
            }

            hb.setStage("window:" + selectedId);

            // Inter-day events skip the ±60-sec window query (Phase 9-A) —
            // their signal is day-level vs trailing baseline, not temporally
            // anchored. The prompt branch in buildUserPrompt handles them
            // with breakdown + catalog only.
            TradeWindow pre, post;
            if (INTER_DAY_SCORERS.contains(row.scorerId)) {
                pre = TradeWindow.empty();
                post = TradeWindow.empty();
            } else {
                // Pre / post window queries. ts_end is null for instantaneous
                // events (large_trade) — use ts as both bounds.
                Timestamp tsStart = Timestamp.from(row.ts);
                Timestamp tsEnd   = (row.tsEnd != null) ? Timestamp.from(row.tsEnd) : tsStart;
                Timestamp preFrom = new Timestamp(tsStart.getTime() - WINDOW_SECONDS * 1000L);
                Timestamp postTo  = new Timestamp(tsEnd.getTime()   + WINDOW_SECONDS * 1000L);
                pre  = TradeWindow.query(conn, row.symbol, preFrom, tsStart);
                post = TradeWindow.query(conn, row.symbol, tsEnd, postTo);
            }
            ObjectNode preJson  = pre.toJson(json);
            ObjectNode postJson = post.toJson(json);

            // INTERPRET-tier derived metrics — cross-window stats the breakdown
            // can't carry (they need the surrounding windows). Attached under
            // postJson.derived so they ride into the verifier haystack + the hash
            // (no verifier-signature change), then surfaced in the prompt.
            // Skipped for inter-day events (empty windows produce no meaningful
            // derived stats).
            ObjectNode derived = json.createObjectNode();
            if (!INTER_DAY_SCORERS.contains(row.scorerId)) {
                derived = computeDerived(json, row, pre, post);
                if (!derived.isEmpty()) postJson.set("derived", derived);
            }

            // Content-addressed skip (same pattern as NarrateEventActivity). The
            // window queries above are cheap SQL; the LLM call below is the
            // expensive part. interpretation_hash folds in breakdown + window
            // summaries + prompt version, so if a verified interpretation exists
            // for these exact inputs we reuse it and skip the LLM.
            //
            // FK re-link on cache hit (2026-05-28 evening): re-scoring a day
            // generates new selected_ids; the cached row's selected_id then
            // points at a deleted selected_events row. Update the existing
            // row's FK to the current selected_id so the API join surfaces it.
            // Same fix pattern as NarrateEventActivityImpl.
            byte[] hash = computeHash(row.scorerId, row.breakdown, preJson, postJson);
            if (NarrateEventActivityImpl.verifiedExists(conn, "interpretations", "interpretation_hash", hash)) {
                relinkInterpretation(conn, hash, selectedId);
                LOG.info("interpret skip (cached, relinked)  selected_id={} scorer={} symbol={}",
                        selectedId, row.scorerId, row.symbol);
                return 1;
            }

            hb.setStage("llm:" + selectedId);

            String userPrompt = buildUserPrompt(row, catalog, pre, post, derived);
            InterpretationVerifier verifier = new InterpretationVerifier();

            // Verifier-driven retry: RENDER runs at temp 0.7, so re-rolling a
            // rejected interpretation (typically a number rendered slightly off
            // from a grounded value) usually grounds on a later attempt. Keep
            // the first passing result; if all attempts fail, keep the last.
            String interpretation = null;
            InterpretationVerifier.Result verify = null;
            long llmElapsedMs = 0;
            for (int attempt = 1; attempt <= MAX_LLM_ATTEMPTS; attempt++) {
                long llmT0 = System.nanoTime();
                interpretation = llama.chat(SYSTEM_PROMPT, userPrompt, SamplingParams.RENDER).trim();
                llmElapsedMs = (System.nanoTime() - llmT0) / 1_000_000L;
                verify = verifier.verify(interpretation, row.breakdown, preJson, postJson, row.symbol, row.scorerId);
                if (verify.passed()) {
                    if (attempt > 1) {
                        LOG.info("interpret verifier passed on retry  selected_id={} symbol={} attempt={}",
                                selectedId, row.symbol, attempt);
                    }
                    break;
                }
                LOG.warn("interpret verifier failed  selected_id={} symbol={} attempt={}/{} mismatches={}",
                        selectedId, row.symbol, attempt, MAX_LLM_ATTEMPTS, verify.mismatches());
            }

            hb.setStage("upsert:" + selectedId);
            upsert(conn, row, interpretation, preJson, postJson, hash, verify, json);

            LOG.info("interpreted  selected_id={} scorer={} symbol={} llm_ms={} verifier_passed={} mismatches={}",
                    selectedId, row.scorerId, row.symbol, llmElapsedMs,
                    verify.passed(), verify.mismatches().size());
            return 1;
        } catch (Exception e) {
            throw new RuntimeException("interpret failed for selected_id=" + selectedId, e);
        }
    }

    private static String buildUserPrompt(final EventRow row,
                                           final Catalog.Entry catalog,
                                           final TradeWindow pre,
                                           final TradeWindow post,
                                           final ObjectNode derived) {
        // Inter-day scorers (volume_deviation, time_in_book_drift) carry a
        // DAY-LEVEL signal, not a temporally-anchored event. The ±60-sec
        // window framing doesn't apply — the breakdown's drift/deviation
        // magnitude + baseline + catalog drivers ARE the full context. Use a
        // different prompt section that asks the model to interpret the
        // magnitude using the catalog's documented drivers, not invent
        // sequential context that doesn't exist.
        if (INTER_DAY_SCORERS.contains(row.scorerId)) {
            return  "Scorer: " + row.scorerId + " (DAY-LEVEL inter-day signal)\n\n"
                  + "Pattern at the wire level:\n"
                  + "  " + catalog.mechanism() + "\n\n"
                  + "Documented drivers (multiple legitimate causes — never a single intent claim):\n"
                  + driversList(catalog) + "\n"
                  + "Breakdown JSON (the day-level measurement):\n"
                  + row.breakdown.toString() + "\n\n"
                  + "Write 1-2 sentences interpreting what today's magnitude means for "
                  + "this symbol, drawing on the drivers above. The drift / deviation is a "
                  + "day-level signal, not a temporally-anchored event — do NOT reference "
                  + "'pre-event' or 'post-event' windows. Use your own framing; the model "
                  + "knows journalist register. Stay grounded — every number must come "
                  + "from the breakdown.";
        }
        return  "Scorer: " + row.scorerId + "\n\n"
              + "Pattern at the wire level:\n"
              + "  " + catalog.mechanism() + "\n\n"
              + "Documented drivers (multiple legitimate causes — never a single intent claim):\n"
              + driversList(catalog) + "\n"
              + "Breakdown JSON (the event's own measurements):\n"
              + row.breakdown.toString() + "\n\n"
              + "Surrounding wire context (same symbol, DPLS feed):\n"
              + "  PRE-EVENT window  (immediately preceding the event): " + pre.toPromptLine() + "\n"
              + "  POST-EVENT window (immediately following the event): " + post.toPromptLine() + "\n"
              + "  DERIVED cross-window metrics: " + derivedLine(derived) + "\n\n"
              + "Write 1-2 sentences identifying the sequential / causal context the "
              + "surrounding data reveals. If both windows are empty / quiet, say so — "
              + "isolation is a valid observation. Use your own framing. Stay grounded — "
              + "every number must come from the breakdown or the surrounding context, "
              + "and do not mention the window size.";
    }

    private static String driversList(final Catalog.Entry catalog) {
        StringBuilder sb = new StringBuilder();
        for (String driver : catalog.documentedDrivers()) {
            sb.append("  - ").append(driver).append('\n');
        }
        return sb.toString();
    }

    /**
     * Inter-day scorer IDs — events without a sharp temporal anchor (the
     * signal is the day's metric vs trailing baseline, not a moment-in-time
     * event). For these, INTERPRET skips the ±60-sec window query and uses
     * a different prompt section. Phase 9-A, 2026-05-28.
     */
    private static final java.util.Set<String> INTER_DAY_SCORERS =
            java.util.Set.of("volume_deviation", "time_in_book_drift");

    /**
     * Cross-window INTERPRET metrics — stats that need the surrounding windows
     * (so they can't live in the score-time breakdown): a scorer-agnostic
     * pre→post VWAP move, and post-event reversion for the price-impact scorers
     * (sweep, large_trade). Reversion is the headline inference: ~100% = the
     * price displacement came back (transient impact, the aggressor overpaid
     * into thin liquidity); ~0% = it held (consistent with informed flow).
     */
    private static ObjectNode computeDerived(final ObjectMapper json, final EventRow row,
                                             final TradeWindow pre, final TradeWindow post) {
        ObjectNode d = json.createObjectNode();
        double preVwap  = vwap(pre);
        double postVwap = vwap(post);
        if (Double.isNaN(preVwap) || Double.isNaN(postVwap) || preVwap <= 0) return d;
        d.put("pre_to_post_vwap_move_bps",
              BreakdownFmt.round((postVwap - preVwap) / preVwap * 10_000.0, 1));
        Double eventPrice = eventPrice(row);
        if (eventPrice != null && eventPrice > 0) {
            double rev = Analytics.reversionFraction(preVwap, eventPrice, postVwap);
            if (!Double.isNaN(rev)) d.put("post_event_reversion_pct", BreakdownFmt.round(rev * 100.0, 0));
        }
        return d;
    }

    private static double vwap(final TradeWindow w) {
        return w.totalShares() > 0 ? (w.totalNotionalRaw() / 10_000.0) / w.totalShares() : Double.NaN;
    }

    /** Representative price the event pushed to, for reversion (price-impact scorers only). */
    private static Double eventPrice(final EventRow row) {
        JsonNode b = row.breakdown;
        switch (row.scorerId) {
            case "sweep" -> {
                JsonNode mn = b.get("min_price_dollars"), mx = b.get("max_price_dollars");
                if (mn != null && mx != null) return (mn.asDouble() + mx.asDouble()) / 2.0;
            }
            case "large_trade" -> {
                JsonNode p = b.get("price_dollars");
                if (p != null) return p.asDouble();
            }
            default -> { }
        }
        return null;
    }

    /** One-line readable form of the derived metrics for the prompt. */
    private static String derivedLine(final ObjectNode d) {
        if (d == null || d.isEmpty()) return "(none — surrounding windows too quiet to derive)";
        StringBuilder sb = new StringBuilder();
        if (d.has("post_event_reversion_pct"))
            sb.append("post-event price reversion ").append(d.get("post_event_reversion_pct").asText()).append("%; ");
        if (d.has("pre_to_post_vwap_move_bps"))
            sb.append("pre→post VWAP move ").append(d.get("pre_to_post_vwap_move_bps").asText()).append(" bps");
        return sb.toString().trim();
    }

    /**
     * Stable hash for {@code interpretations.interpretation_hash}. Includes
     * the prompt version so a prompt change invalidates the cache; includes
     * the model id so an A/B between two models writes two rows.
     */
    private static byte[] computeHash(final String scorerId,
                                       final JsonNode breakdown,
                                       final ObjectNode preJson,
                                       final ObjectNode postJson) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(scorerId.getBytes(StandardCharsets.UTF_8));
        md.update((byte) '\0');
        md.update(breakdown.toString().getBytes(StandardCharsets.UTF_8));
        md.update((byte) '\0');
        md.update(preJson.toString().getBytes(StandardCharsets.UTF_8));
        md.update((byte) '\0');
        md.update(postJson.toString().getBytes(StandardCharsets.UTF_8));
        md.update((byte) '\0');
        md.update(PROMPT_VERSION.getBytes(StandardCharsets.UTF_8));
        md.update((byte) '\0');
        md.update(MODEL_ID.getBytes(StandardCharsets.UTF_8));
        return md.digest();
    }

    private EventRow loadSelectedEvent(final Connection conn,
                                        final LocalDate tradingDate,
                                        final long selectedId,
                                        final ObjectMapper json) throws Exception {
        String sql = """
                SELECT selected_id, trading_date, scorer_id, symbol,
                       ts, ts_end, score, breakdown::text
                FROM selected_events
                WHERE trading_date = ? AND selected_id = ?
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, tradingDate);
            st.setLong(2, selectedId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) return null;
                EventRow r = new EventRow();
                r.selectedId  = rs.getLong("selected_id");
                r.tradingDate = tradingDate;
                r.scorerId    = rs.getString("scorer_id");
                r.symbol      = rs.getString("symbol");
                r.ts          = rs.getTimestamp("ts").toInstant();
                Timestamp endTs = rs.getTimestamp("ts_end");
                r.tsEnd       = (endTs != null) ? endTs.toInstant() : null;
                r.score       = rs.getDouble("score");
                r.breakdown   = json.readTree(rs.getString("breakdown"));
                return r;
            }
        }
    }

    private static void upsert(final Connection conn,
                                final EventRow row,
                                final String interpretation,
                                final ObjectNode preJson,
                                final ObjectNode postJson,
                                final byte[] hash,
                                final InterpretationVerifier.Result verify,
                                final ObjectMapper json) throws Exception {
        ObjectNode verifierNotes = json.createObjectNode();
        verifierNotes.put("numbers_checked", verify.numbersChecked());
        verifierNotes.set("mismatches", json.valueToTree(verify.mismatches()));

        String sql = """
                INSERT INTO interpretations (
                    interpretation_hash, trading_date, selected_id, event_type,
                    symbol, event_ts, event_ts_end, score, interpretation,
                    pre_window_summary, post_window_summary, model_id,
                    verifier_passed, verifier_notes
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?::jsonb)
                ON CONFLICT (interpretation_hash) DO UPDATE SET
                    interpretation      = EXCLUDED.interpretation,
                    pre_window_summary  = EXCLUDED.pre_window_summary,
                    post_window_summary = EXCLUDED.post_window_summary,
                    verifier_passed     = EXCLUDED.verifier_passed,
                    verifier_notes      = EXCLUDED.verifier_notes,
                    model_id            = EXCLUDED.model_id,
                    created_at          = NOW()
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setBytes(1, hash);
            st.setObject(2, row.tradingDate);
            st.setLong(3, row.selectedId);
            st.setString(4, row.scorerId);
            if (row.symbol != null) st.setString(5, row.symbol); else st.setNull(5, Types.VARCHAR);
            st.setTimestamp(6, Timestamp.from(row.ts));
            if (row.tsEnd != null) st.setTimestamp(7, Timestamp.from(row.tsEnd)); else st.setNull(7, Types.TIMESTAMP);
            st.setDouble(8, row.score);
            st.setString(9, interpretation);
            st.setString(10, preJson.toString());
            st.setString(11, postJson.toString());
            st.setString(12, MODEL_ID);
            st.setBoolean(13, verify.passed());
            st.setString(14, verifierNotes.toString());
            st.executeUpdate();
        }
    }

    /**
     * On cache hit, refresh the row's FK to point at the current selected_id.
     * Same shape as {@code NarrateEventActivityImpl.relinkNarrative}; see
     * that comment for the FK orphaning rationale.
     */
    private static void relinkInterpretation(final Connection conn, final byte[] hash,
                                              final long currentSelectedId) throws Exception {
        String sql = "UPDATE interpretations SET selected_id = ? "
                   + "WHERE interpretation_hash = ? AND verifier_passed = true";
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setLong(1, currentSelectedId);
            st.setBytes(2, hash);
            st.executeUpdate();
        }
    }

    private static Connection openConnection() throws Exception {
        String host = System.getenv().getOrDefault("POSTGRES_HOST", "localhost");
        String port = System.getenv().getOrDefault("POSTGRES_PORT", "5432");
        String db   = System.getenv().getOrDefault("POSTGRES_DB", "longexposure");
        String user = System.getenv().getOrDefault("POSTGRES_USER", "leuser");
        String pwd  = System.getenv().getOrDefault("POSTGRES_PASSWORD", "lepass");
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + db;
        return DriverManager.getConnection(url, user, pwd);
    }

    /** Plain mutable struct — only used inside this activity. */
    private static final class EventRow {
        long       selectedId;
        LocalDate  tradingDate;
        String     scorerId;
        String     symbol;
        java.time.Instant ts;
        java.time.Instant tsEnd;
        double     score;
        JsonNode   breakdown;
    }
}
