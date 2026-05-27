package com.longexposure.temporal.activities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.longexposure.llm.LlamaClient;
import com.longexposure.llm.SamplingParams;
import com.longexposure.narration.Catalog;
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
    private static final String PROMPT_VERSION = "interpret-v7-holistic-approx-example";

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

        try (Connection conn = openConnection()) {
            SchemaManager.apply(conn);

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

            actx.heartbeat("window:" + selectedId);

            // Pre / post window queries. ts_end is null for instantaneous
            // events (large_trade) — use ts as both bounds.
            Timestamp tsStart = Timestamp.from(row.ts);
            Timestamp tsEnd   = (row.tsEnd != null) ? Timestamp.from(row.tsEnd) : tsStart;
            Timestamp preFrom = new Timestamp(tsStart.getTime() - WINDOW_SECONDS * 1000L);
            Timestamp postTo  = new Timestamp(tsEnd.getTime()   + WINDOW_SECONDS * 1000L);

            TradeWindow pre  = TradeWindow.query(conn, row.symbol, preFrom, tsStart);
            TradeWindow post = TradeWindow.query(conn, row.symbol, tsEnd, postTo);
            ObjectNode preJson  = pre.toJson(json);
            ObjectNode postJson = post.toJson(json);

            // Content-addressed skip (same pattern as NarrateEventActivity). The
            // window queries above are cheap SQL; the LLM call below is the
            // expensive part. interpretation_hash folds in breakdown + window
            // summaries + prompt version, so if a verified interpretation exists
            // for these exact inputs we reuse it and skip the LLM.
            byte[] hash = computeHash(row.scorerId, row.breakdown, preJson, postJson);
            if (NarrateEventActivityImpl.verifiedExists(conn, "interpretations", "interpretation_hash", hash)) {
                LOG.info("interpret skip (cached)  selected_id={} scorer={} symbol={}",
                        selectedId, row.scorerId, row.symbol);
                return 1;
            }

            actx.heartbeat("llm:" + selectedId);

            String userPrompt = buildUserPrompt(row, catalog, pre, post);
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
                verify = verifier.verify(interpretation, row.breakdown, preJson, postJson, row.symbol);
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

            actx.heartbeat("upsert:" + selectedId);
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
                                           final TradeWindow post) {
        return  "Scorer: " + row.scorerId + "\n\n"
              + "Catalog entry for this scorer:\n"
              + "  mechanism: " + catalog.mechanism() + "\n"
              + "  canonical_interpretation: " + catalog.canonicalInterpretation() + "\n\n"
              + "Breakdown JSON (the event's own measurements):\n"
              + row.breakdown.toString() + "\n\n"
              + "Surrounding wire context (same symbol, DPLS feed):\n"
              + "  PRE-EVENT window  (immediately preceding the event): " + pre.toPromptLine() + "\n"
              + "  POST-EVENT window (immediately following the event): " + post.toPromptLine() + "\n\n"
              + "Now write 1-2 sentences identifying the sequential / causal context "
              + "the surrounding data reveals. If both windows are empty / quiet, say "
              + "so — isolation is a valid observation. Stay grounded — every number "
              + "must come from the breakdown or the surrounding context, and do not "
              + "mention the window size.";
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
