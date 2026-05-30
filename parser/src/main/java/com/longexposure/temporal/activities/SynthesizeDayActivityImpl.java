package com.longexposure.temporal.activities;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.longexposure.llm.LlamaClient;
import com.longexposure.llm.SamplingParams;
import com.longexposure.narration.SynthesisVerifier;
import com.longexposure.storage.SchemaManager;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * SYNTHESIZE implementation — one LLM call per day. See {@link SynthesizeDayActivity}
 * for the contract.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Load all narratives + interpretations for the date (LEFT JOIN —
 *       events without an interpretation still contribute)
 *   <li>Compute day aggregates: per-scorer counts, top symbols by event
 *       count, time-of-day distribution, total halt count
 *   <li>Build the prompt: system rules + day metadata + numbered list of
 *       per-event narration/interpretation pairs
 *   <li>One LLM call with {@link SamplingParams#SYNTHESIZE} preset
 *   <li>Run {@link SynthesisVerifier} (ticker fabrication + number grounding)
 *   <li>Upsert into {@code daily_synthesis} (PK = trading_date, replaces on re-run)
 * </ol>
 */
public final class SynthesizeDayActivityImpl implements SynthesizeDayActivity {

    private static final Logger LOG = LoggerFactory.getLogger(SynthesizeDayActivityImpl.class);

    private static final String MODEL_ID = System.getenv()
            .getOrDefault("LLAMA_MODEL", "Qwen3.5-122B-A10B");

    /**
     * Bumped when prompt changes — also bumped on verifier changes that
     * invalidate prior verdicts. v7 (2026-05-28 later same day) is a
     * VERIFIER-ONLY change: {@link AttributionVerifier} added as a third
     * verification layer on top of ticker fabrication + number grounding +
     * intent denylist. Catches misattribution: prose that says "TQQQ with
     * ten X events" when (TQQQ, X) actually had 20. Activity computes
     * by_symbol_by_scorer + by_symbol_total maps and passes them to the
     * verifier; they are NOT exposed in the LLM-facing JSON (per project
     * structural-fix discipline — band-aid avoidance). Prompt unchanged
     * from v6.
     *
     * <p>v6 (earlier 2026-05-28) extended grounding to cardinal word-form
     * numerals ("six halts" / "ten layering"). The 05-12 audit found 5/5
     * word-form counts in that day's synthesis were wrong and bypassed
     * verification entirely because the regex was digit-only.
     *
     * <p>An earlier v7 prototype (by_scorer_by_symbol JSON + "ATTRIBUTING
     * COUNTS" prompt section) was reverted same day — the prompt section
     * was prompt-engineering band-aid; the JSON-only would have made
     * misattribution slightly worse. This v7 supersedes it via the
     * structural verifier approach.
     */
    private static final String PROMPT_VERSION = "synthesize-v10-qualitative-themes-2026-05-30";

    /** Max LLM attempts per day — re-roll on verifier failure (temp 1.0 gives variance). */
    private static final int MAX_LLM_ATTEMPTS = 3;

    private static final String SYSTEM_PROMPT = """
            You are a financial-data journalist writing one paragraph identifying
            the recurring themes of a single US-equity trading day on IEX. Your
            output is the top-of-page summary for that date's market-microstructure
            feed.

            INPUTS:
              - DAY METADATA: trading date, total events, count per scorer type,
                top symbols by event count, distribution across session phases.
              - PER-EVENT LIST: each event's per-event interpretation prose,
                in chronological order with symbol, scorer type, and event time.

            OUTPUT: one paragraph (3-6 sentences, ~400-700 chars). Financial-journalist
            register (FT / Bloomberg). Acronyms (LULD, NBBO, MCB, VWAP, HFT) glossed
            on first use. No preamble — lead with the most notable theme.

            WHAT THIS PARAGRAPH IS FOR:

            Identify cross-event patterns that no per-event view can surface:
            time-of-day concentration ("the open saw heavy activity in 3x
            leveraged ETFs"), cross-symbol coherence ("semiconductors had paired
            large blocks throughout the session"), scorer-type clustering on
            individual symbols ("INTC saw layering, post-cancel bursts, and
            sweeps"), regime shifts across the session ("the morning's active
            layering quieted to afternoon institutional blocks"), and notable
            individual events worth surfacing by name.

            QUALITATIVE-ONLY RULE — read this carefully:

            You may NOT enumerate events per symbol or per scorer type. Specifically:

            DO NOT write phrasings like:
              - "TQQQ had 8 liquidity withdrawals"
              - "seven halts occurred"
              - "QQQ and SPY accounted for 12 events"
              - "the four large trades"
              - any sentence of shape (subject + cardinal number + scorer-type)

            INSTEAD use qualitative magnitude language:
              - "TQQQ saw heavy / frequent / repeated / sustained liquidity withdrawals"
              - "multiple halts clustered at the open"
              - "QQQ and SPY dominated the day's activity"
              - "the day's large-trade activity centered on semiconductors"

            Counts of events are not what the reader needs from this paragraph —
            the per-event prose below already exposes them. The paragraph's job is
            CHARACTER: time-of-day shape, sector clustering, regime shifts,
            individual standout events.

            GROUNDING — the primary rule:

            Every ticker you mention must appear in today's narrations. Every
            specific numeric claim must trace to a specific per-event interpretation
            (e.g., a dollar value, a duration, a percentage that appears in the
            per-event list). The trading date is provided in the day metadata;
            do not invent or restate it in a different format. Do not introduce
            numbers from outside the inputs.

            REGISTER:

            You have no news source: the data shows wire activity, not its cause.
            Do not claim external events ("the FOMC announced", "earnings beats",
            "geopolitical tension"). Do not speculate about intent ("the algorithm
            was trying to X"). Do not editorialize about severity ("striking",
            "wild volatility"). Do not compare to other days — you only have this
            one.
            """;

    @Override
    public long synthesize(final LocalDate tradingDate) {
        ActivityExecutionContext actx = Activity.getExecutionContext();
        ObjectMapper json = new ObjectMapper();
        LlamaClient llama = LlamaClient.fromEnv();

        // Background heartbeat — the blocking LLM HTTP call runs 30-90s with
        // no yield points; without this thread the heartbeat-timeout fires
        // before the call returns. Replaces the band-aid 5-min heartbeat
        // workaround from commit 15af21f.
        try (BackgroundHeartbeat hb = BackgroundHeartbeat.start(actx, "synth-day-heartbeat", 30);
             Connection conn = openConnection()) {
            SchemaManager.apply(conn);

            hb.setStage("load");
            List<EventRow> events = loadEventsForDay(conn, tradingDate);
            if (events.isEmpty()) {
                LOG.warn("no narrations for date — skipping synthesis  date={}", tradingDate);
                return 0;
            }

            hb.setStage("aggregate");
            // Attribution-truth maps populated by computeDayAggregates. NOT
            // exposed in the LLM-facing JSON (per the project's structural-
            // fix discipline); only consumed by AttributionVerifier inside
            // the SynthesisVerifier.verify() call below.
            Map<String, Map<String, Integer>> bySymbolByScorer = new HashMap<>();
            Map<String, Integer> bySymbolTotal = new HashMap<>();
            ObjectNode dayAggregates = computeDayAggregates(
                    events, json, bySymbolByScorer, bySymbolTotal);
            Set<String> daySymbols = new HashSet<>();
            for (EventRow e : events) if (e.symbol != null) daySymbols.add(e.symbol);

            hb.setStage("prompt");
            String userPrompt = buildUserPrompt(tradingDate, dayAggregates, events,
                    bySymbolByScorer, bySymbolTotal);

            String numberHaystack = buildNumberHaystack(events, dayAggregates);
            SynthesisVerifier verifier = new SynthesisVerifier();

            // Verifier-driven retry: re-roll on a rejected synthesis (a derived
            // or slightly-off number, a mis-tokenized ticker) — SYNTHESIZE runs
            // at temp 1.0, so a re-roll usually grounds. Keep first passing,
            // else last.
            String synthesis = null;
            SynthesisVerifier.Result verify = null;
            long llmElapsedMs = 0;
            for (int attempt = 1; attempt <= MAX_LLM_ATTEMPTS; attempt++) {
                hb.setStage("llm:attempt-" + attempt);
                long llmT0 = System.nanoTime();
                synthesis = llama.chat(SYSTEM_PROMPT, userPrompt, SamplingParams.SYNTHESIZE).trim();
                llmElapsedMs = (System.nanoTime() - llmT0) / 1_000_000L;
                hb.setStage("verify:attempt-" + attempt);
                verify = verifier.verify(synthesis, daySymbols, numberHaystack,
                        bySymbolByScorer, bySymbolTotal);
                if (verify.passed()) {
                    if (attempt > 1) LOG.info("synthesis verifier passed on retry  date={} attempt={}", tradingDate, attempt);
                    break;
                }
                LOG.warn("synthesis verifier failed  date={} attempt={}/{} mismatches={}",
                        tradingDate, attempt, MAX_LLM_ATTEMPTS, verify.mismatches());
            }

            hb.setStage("upsert");
            upsert(conn, tradingDate, synthesis, dayAggregates, events.size(),
                   countWithNarration(events), countWithInterpretation(events),
                   verify, json);

            LOG.info("synthesized  date={} events={} llm_ms={} verifier_passed={} mismatches={}",
                    tradingDate, events.size(), llmElapsedMs,
                    verify.passed(), verify.mismatches().size());
            return 1;
        } catch (Exception e) {
            throw new RuntimeException("synthesize failed for date=" + tradingDate, e);
        }
    }

    /**
     * All scored+selected events for the date paired with the LATEST narration
     * and interpretation per event. The DISTINCT-ON sub-queries keep one row
     * per selected_id even when multiple narrations/interpretations exist
     * (from prompt-version iterations that produced new rows with different
     * hashes). Without this filter the LEFT JOIN cross-products N narrations
     * × M interpretations per event, blowing the prompt past joi's 32K
     * context budget.
     */
    private List<EventRow> loadEventsForDay(final Connection conn, final LocalDate tradingDate) throws Exception {
        String sql = """
                SELECT se.selected_id, se.scorer_id, se.symbol, se.ts,
                       n.narrative, i.interpretation
                FROM selected_events se
                LEFT JOIN (
                    SELECT DISTINCT ON (selected_id) selected_id, narrative
                    FROM narratives
                    WHERE trading_date = ?
                    ORDER BY selected_id, created_at DESC
                ) n ON n.selected_id = se.selected_id
                LEFT JOIN (
                    SELECT DISTINCT ON (selected_id) selected_id, interpretation
                    FROM interpretations
                    WHERE trading_date = ?
                    ORDER BY selected_id, created_at DESC
                ) i ON i.selected_id = se.selected_id
                WHERE se.trading_date = ?
                ORDER BY se.ts
                """;
        List<EventRow> out = new ArrayList<>();
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            // Three bind sites: narratives subquery, interpretations subquery,
            // outer selected_events filter — all the same trading_date.
            st.setObject(1, tradingDate);
            st.setObject(2, tradingDate);
            st.setObject(3, tradingDate);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    EventRow r = new EventRow();
                    r.selectedId = rs.getLong("selected_id");
                    r.scorerId   = rs.getString("scorer_id");
                    r.symbol     = rs.getString("symbol");
                    r.ts         = rs.getTimestamp("ts");
                    r.narration  = rs.getString("narrative");
                    r.interpretation = rs.getString("interpretation");
                    out.add(r);
                }
            }
        }
        return out;
    }

    /**
     * Per-scorer counts + top symbols by event count + time-of-day buckets.
     * Also populates the verifier's attribution truth maps via the supplied
     * out-parameters — those are NOT included in the JSON sent to the LLM
     * (band-aid risk per docs/decisions.md), only consumed by
     * {@link AttributionVerifier} for the per-claim grounding check.
     *
     * @param events            today's selected events
     * @param json              object-mapper for JSON construction
     * @param outBySymbolScorer (out) per-symbol per-scorer counts (truth)
     * @param outBySymbolTotal  (out) per-symbol total counts (truth)
     */
    private ObjectNode computeDayAggregates(final List<EventRow> events,
                                            final ObjectMapper json,
                                            final Map<String, Map<String, Integer>> outBySymbolScorer,
                                            final Map<String, Integer> outBySymbolTotal) {
        Map<String, Integer> byScorer = new TreeMap<>();
        Map<String, Integer> bySymbol = new HashMap<>();
        Map<String, Integer> byPhase  = new TreeMap<>();
        for (EventRow e : events) {
            byScorer.merge(e.scorerId, 1, Integer::sum);
            if (e.symbol != null) {
                bySymbol.merge(e.symbol, 1, Integer::sum);
                outBySymbolScorer
                        .computeIfAbsent(e.symbol, k -> new TreeMap<>())
                        .merge(e.scorerId, 1, Integer::sum);
            }
            String phase = com.longexposure.scoring.BreakdownFmt.sessionPhase(e.ts.toInstant());
            if (phase != null) byPhase.merge(phase, 1, Integer::sum);
        }
        outBySymbolTotal.putAll(bySymbol);

        // Top 10 symbols by event count
        List<Map.Entry<String, Integer>> topSyms = new ArrayList<>(bySymbol.entrySet());
        topSyms.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        if (topSyms.size() > 10) topSyms = topSyms.subList(0, 10);

        ObjectNode agg = json.createObjectNode();
        agg.put("total_events", events.size());

        ObjectNode scorerNode = agg.putObject("by_scorer");
        for (Map.Entry<String, Integer> e : byScorer.entrySet()) scorerNode.put(e.getKey(), e.getValue());

        ObjectNode phaseNode = agg.putObject("by_session_phase");
        for (Map.Entry<String, Integer> e : byPhase.entrySet()) phaseNode.put(e.getKey(), e.getValue());

        ArrayNode topSymNode = agg.putArray("top_symbols_by_event_count");
        for (Map.Entry<String, Integer> e : topSyms) {
            ObjectNode s = topSymNode.addObject();
            s.put("symbol", e.getKey());
            s.put("count", e.getValue());
        }

        // Day-level concentration + breadth (the SYNTHESIZE/AGGREGATE-tier stats):
        // HHI of events across symbols (→1 = a few names dominated the day) and
        // normalized entropy of the scorer-type mix (→0 = a one-note day, e.g.
        // mostly liquidity withdrawals; →1 = an even spread across pattern types).
        double hhi = com.longexposure.analytics.Analytics.hhi(
                bySymbol.values().stream().mapToDouble(Integer::doubleValue).toArray());
        if (!Double.isNaN(hhi)) agg.put("symbol_concentration_hhi",
                com.longexposure.scoring.BreakdownFmt.round(hhi, 3));
        double ent = com.longexposure.analytics.Analytics.normalizedEntropy(
                byScorer.values().stream().mapToDouble(Integer::doubleValue).toArray());
        if (!Double.isNaN(ent)) agg.put("scorer_mix_entropy",
                com.longexposure.scoring.BreakdownFmt.round(ent, 2));
        return agg;
    }

    private String buildUserPrompt(final LocalDate tradingDate,
                                   final ObjectNode dayAggregates,
                                   final List<EventRow> events,
                                   final Map<String, Map<String, Integer>> bySymbolByScorer,
                                   final Map<String, Integer> bySymbolTotal) {
        // v10 (2026-05-30): the PER-SYMBOL COUNTS truth table that v8/v9
        // attempted to inject is GONE. Qwen-122B was ignoring it ~33% of the
        // time and fabricating counts anyway; the AttributionVerifier then
        // caught the fabrications and the retry mechanism re-rolled fresh
        // fabrications. Structural fix: remove count-claim language from the
        // task by construction. The system prompt now forbids "X had N events"
        // shape entirely; what's left is qualitative theme identification,
        // which the model can do reliably. Truth maps still flow through to
        // SynthesisVerifier so AttributionVerifier still catches any residual
        // count claims that slip through — but for v10 the prompt should
        // produce zero such claims.
        //
        // Compact JSON for day aggregates (toPrettyString adds whitespace that
        // costs ~3-4× the token count). INTERP-only per-event entries to fit
        // joi's n_ctx=32768 budget; INTERP restates the descriptive content +
        // adds sequential context, so DESC is redundant. Falls back to DESC
        // if INTERP is null (rare — < 2% on the 2-week dataset).
        StringBuilder sb = new StringBuilder(64 * 1024);
        sb.append("Trading date: ").append(tradingDate).append("\n\n");
        sb.append("DAY METADATA: ").append(dayAggregates.toString()).append("\n\n");

        sb.append("PER-EVENT LIST (chronological, ").append(events.size()).append(" events):\n\n");
        int idx = 0;
        for (EventRow e : events) {
            idx++;
            String body = (e.interpretation != null && !e.interpretation.isBlank())
                          ? e.interpretation.trim()
                          : (e.narration != null ? e.narration.trim() : "(no narration)");
            sb.append("[").append(idx).append("] ").append(e.scorerId)
              .append(" · ").append(e.symbol == null ? "—" : e.symbol)
              .append(" · ").append(com.longexposure.scoring.BreakdownFmt.toEtTime(e.ts.toInstant()))
              .append(" ET — ").append(body).append("\n");
        }
        sb.append('\n');

        sb.append("Now write the day's themes paragraph (3-6 sentences, journalist register).\n");
        sb.append("Constraints (re-stating the SYSTEM prompt's rules — read carefully):\n");
        sb.append("  - QUALITATIVE language about activity volume: 'heavy', 'multiple',\n");
        sb.append("    'frequent', 'sustained', 'dominated', 'clustered'. NEVER specific counts.\n");
        sb.append("  - Specific numbers are allowed ONLY if they appear in a per-event\n");
        sb.append("    interpretation above (a dollar value, duration, percentage).\n");
        sb.append("  - Every ticker mentioned must appear in today's per-event list.\n");
        sb.append("  - No intent claims ('manipulation', 'spoofing'), no external news,\n");
        sb.append("    no comparison to other days.\n");
        return sb.toString();
    }

    /**
     * Concatenated text for the SynthesisVerifier's number-grounding check.
     * Uses {@link com.longexposure.narration.GroundingVerifier#appendAllValues}
     * to flatten the day-aggregates JSON — that helper handles numeric nodes
     * via {@code BigDecimal.toPlainString()}, avoiding the scientific-notation
     * trap that {@code dayAggregates.toString()} would hit on large doubles
     * (Jackson serializes magnitudes ≥ 1e7 as "1.069414005E7").
     */
    private String buildNumberHaystack(final List<EventRow> events, final ObjectNode dayAggregates) {
        StringBuilder sb = new StringBuilder(64 * 1024);
        com.longexposure.narration.GroundingVerifier.appendAllValues(dayAggregates, sb);
        sb.append('\n');
        for (EventRow e : events) {
            if (e.narration != null)      sb.append(e.narration).append('\n');
            if (e.interpretation != null) sb.append(e.interpretation).append('\n');
        }
        return sb.toString();
    }

    private void upsert(final Connection conn,
                        final LocalDate tradingDate,
                        final String synthesis,
                        final ObjectNode dayAggregates,
                        final int eventsConsidered,
                        final int narrationsConsidered,
                        final int interpretationsConsidered,
                        final SynthesisVerifier.Result verify,
                        final ObjectMapper json) throws Exception {
        ObjectNode verifierNotes = json.createObjectNode();
        verifierNotes.put("tickers_checked", verify.tickersChecked());
        verifierNotes.put("numbers_checked", verify.numbersChecked());
        verifierNotes.set("mismatches", json.valueToTree(verify.mismatches()));

        String sql = """
                INSERT INTO daily_synthesis (
                    trading_date, synthesis_text, events_considered,
                    narrations_considered, interpretations_considered,
                    day_aggregates, model_id, prompt_version,
                    verifier_passed, verifier_notes
                )
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb)
                ON CONFLICT (trading_date) DO UPDATE SET
                    synthesis_text             = EXCLUDED.synthesis_text,
                    events_considered          = EXCLUDED.events_considered,
                    narrations_considered      = EXCLUDED.narrations_considered,
                    interpretations_considered = EXCLUDED.interpretations_considered,
                    day_aggregates             = EXCLUDED.day_aggregates,
                    model_id                   = EXCLUDED.model_id,
                    prompt_version             = EXCLUDED.prompt_version,
                    verifier_passed            = EXCLUDED.verifier_passed,
                    verifier_notes             = EXCLUDED.verifier_notes,
                    created_at                 = NOW()
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, tradingDate);
            st.setString(2, synthesis);
            st.setInt(3, eventsConsidered);
            st.setInt(4, narrationsConsidered);
            st.setInt(5, interpretationsConsidered);
            st.setString(6, dayAggregates.toString());
            st.setString(7, MODEL_ID);
            st.setString(8, PROMPT_VERSION);
            st.setBoolean(9, verify.passed());
            st.setString(10, verifierNotes.toString());
            st.executeUpdate();
        }
    }

    private static int countWithNarration(final List<EventRow> events) {
        int n = 0; for (EventRow e : events) if (e.narration != null) n++; return n;
    }
    private static int countWithInterpretation(final List<EventRow> events) {
        int n = 0; for (EventRow e : events) if (e.interpretation != null) n++; return n;
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

    private static final class EventRow {
        long selectedId;
        String scorerId;
        String symbol;
        Timestamp ts;
        String narration;
        String interpretation;
    }
}
