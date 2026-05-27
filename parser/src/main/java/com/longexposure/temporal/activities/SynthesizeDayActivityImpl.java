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

    /** Bumped when prompt changes. */
    private static final String PROMPT_VERSION = "synthesize-v5-holistic-revert-v4-example";

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

            GROUNDING — the primary rule:

            Every ticker you mention must appear in today's narrations. Every
            numeric claim must trace to either the day metadata or a specific
            per-event interpretation — no introducing numbers from outside the
            inputs, no approximation or rounding. The trading date is provided
            in the day metadata; do not invent or restate it in a different
            format.

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

        try (Connection conn = openConnection()) {
            SchemaManager.apply(conn);

            actx.heartbeat("load");
            List<EventRow> events = loadEventsForDay(conn, tradingDate);
            if (events.isEmpty()) {
                LOG.warn("no narrations for date — skipping synthesis  date={}", tradingDate);
                return 0;
            }

            actx.heartbeat("aggregate");
            ObjectNode dayAggregates = computeDayAggregates(events, json);
            Set<String> daySymbols = new HashSet<>();
            for (EventRow e : events) if (e.symbol != null) daySymbols.add(e.symbol);

            actx.heartbeat("prompt");
            String userPrompt = buildUserPrompt(tradingDate, dayAggregates, events);

            String numberHaystack = buildNumberHaystack(events, dayAggregates);
            SynthesisVerifier verifier = new SynthesisVerifier();

            // Verifier-driven retry: re-roll on a rejected synthesis (a derived
            // or slightly-off number, a mis-tokenized ticker) — SYNTHESIZE runs
            // at temp 1.0, so a re-roll usually grounds. Keep first passing,
            // else last.
            actx.heartbeat("llm");
            String synthesis = null;
            SynthesisVerifier.Result verify = null;
            long llmElapsedMs = 0;
            for (int attempt = 1; attempt <= MAX_LLM_ATTEMPTS; attempt++) {
                long llmT0 = System.nanoTime();
                synthesis = llama.chat(SYSTEM_PROMPT, userPrompt, SamplingParams.SYNTHESIZE).trim();
                llmElapsedMs = (System.nanoTime() - llmT0) / 1_000_000L;
                verify = verifier.verify(synthesis, daySymbols, numberHaystack);
                if (verify.passed()) {
                    if (attempt > 1) LOG.info("synthesis verifier passed on retry  date={} attempt={}", tradingDate, attempt);
                    break;
                }
                LOG.warn("synthesis verifier failed  date={} attempt={}/{} mismatches={}",
                        tradingDate, attempt, MAX_LLM_ATTEMPTS, verify.mismatches());
            }

            actx.heartbeat("upsert");
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

    /** Per-scorer counts + top symbols by event count + time-of-day buckets. */
    private ObjectNode computeDayAggregates(final List<EventRow> events, final ObjectMapper json) {
        Map<String, Integer> byScorer = new TreeMap<>();
        Map<String, Integer> bySymbol = new HashMap<>();
        Map<String, Integer> byPhase  = new TreeMap<>();
        for (EventRow e : events) {
            byScorer.merge(e.scorerId, 1, Integer::sum);
            if (e.symbol != null) bySymbol.merge(e.symbol, 1, Integer::sum);
            String phase = com.longexposure.scoring.BreakdownFmt.sessionPhase(e.ts.toInstant());
            if (phase != null) byPhase.merge(phase, 1, Integer::sum);
        }

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
        return agg;
    }

    private String buildUserPrompt(final LocalDate tradingDate,
                                   final ObjectNode dayAggregates,
                                   final List<EventRow> events) {
        // Compact JSON for the day aggregates (toPrettyString adds whitespace
        // that costs ~3-4× the token count). INTERP-only per-event entries to
        // fit within the joi llama.cpp server's n_ctx=32768 budget; INTERP
        // already restates the descriptive content plus adds sequential
        // context, so DESC is redundant for synthesis. Falls back to DESC if
        // INTERP is null (rare — 162/164 had INTERP on 2026-05-08).
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
        sb.append("\nNow write the day's themes paragraph (3-6 sentences, journalist register, "
                + "ground every claim in the data above).");
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
