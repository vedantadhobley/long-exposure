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
    private static final String PROMPT_VERSION = "synthesize-v13-holistic-refactor-2026-05-31";

    /** Max LLM attempts per day — re-roll on verifier failure (temp 1.0 gives variance). */
    private static final int MAX_LLM_ATTEMPTS = 3;

    private static final String SYSTEM_PROMPT = """
            You are a financial-data journalist (FT / Bloomberg register) writing
            ONE paragraph identifying the recurring themes of a single US-equity
            trading day on IEX. Your paragraph is the top-of-page summary for
            that date.

            OUTPUT: one paragraph, 3-6 sentences, ~400-700 chars. Lead with the
            most notable theme of the regular session (09:30-16:00 ET) — that's
            where the day's actual story lives. Gloss acronyms (LULD, NBBO,
            MCB, VWAP, HFT) on first use.

            INPUTS (in the user prompt):
              - SESSION SHAPE: per-phase event counts (pre-market through close)
              - SCORER COUNTS: how many events of each detected type
              - DAY-LEVEL SIGNALS: inter-day metrics (volume_deviation,
                time_in_book_drift) — these are whole-day measurements with
                no clock anchor, NOT temporal events
              - INTRADAY EVENTS: grouped by session phase, score-ordered within
                each phase, with the per-event INTERPRET prose

            WHAT THE PARAGRAPH IDENTIFIES:
              - cross-event patterns no per-event view can surface
              - time-of-day character (where activity concentrated)
              - cross-symbol coherence (sector clustering, ETF-family flow)
              - scorer-type clustering on individual symbols
              - notable individual events worth surfacing by name

            QUALITATIVE-ONLY RULE: do NOT enumerate counts per symbol or scorer
            type ("TQQQ had 8 X events", "seven halts occurred"). Use qualitative
            magnitude language ("heavy", "sustained", "dominated", "clustered").
            The per-event prose in the user prompt already exposes the counts;
            your paragraph's job is CHARACTER.

            GROUNDING:
              - Every ticker must appear in the user prompt's events.
              - Every specific number must trace to a specific per-event entry
                (a dollar value, duration, percentage that appears below).
              - No external causes (news, FOMC, earnings, geopolitics).
              - No intent claims ("the algorithm was X-ing", "spoofing").
              - No comparison to other days — you only have this one.
              - No editorializing severity ("striking", "wild").

            CANONICAL VOCABULARY (consistency across narrations is load-bearing):
              - Baselines: "the trailing 2-week median". NEVER literal day
                counts ("14-day", "10-day") — the window varies by symbol.
              - Multipliers: "22.2x the trailing median" (1-decimal + "x").
              - Slippage: "X bps slippage" / "slipped X bps".
              - Depth removal: "removed X% of displayed depth".
              - Iceberg ratio: "the displayed tip represented N% of total executed".
              - Order-to-trade when infinite: "no fills against N posted orders".

            These govern PHRASING, not values. The per-event prose already uses
            this vocabulary; mirror it when referencing their metrics.

            STYLE: vary your lede. The model has good journalist instincts —
            use them. Do not reuse the same opening framing or vocabulary across
            successive days' paragraphs.
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

            hb.setStage("build_data_table");
            // Build the structured data table — pure SQL, no LLM. Renders ABOVE
            // the prose on the per-day view. See DailyDataTableBuilder.
            ObjectNode dataTable;
            try {
                dataTable = com.longexposure.synth.DailyDataTableBuilder.build(conn, tradingDate, json);
            } catch (Exception e) {
                LOG.warn("data_table build failed (non-fatal)  date={} err={}", tradingDate, e.getMessage());
                dataTable = json.createObjectNode();
                dataTable.put("trading_date", tradingDate.toString());
                dataTable.put("build_error", e.getMessage());
            }

            hb.setStage("upsert");
            upsert(conn, tradingDate, synthesis, dayAggregates, events.size(),
                   countWithNarration(events), countWithInterpretation(events),
                   verify, dataTable, json);

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
                SELECT se.selected_id, se.scorer_id, se.symbol, se.ts, se.score,
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
                    r.score      = rs.getDouble("score");
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

        // SESSION SHAPE — phase distribution so model sees proportions before events.
        sb.append("SESSION SHAPE (event counts by phase):\n");
        com.fasterxml.jackson.databind.JsonNode byPhase = dayAggregates.path("by_session_phase");
        // Render in chronological phase order for readability.
        String[] phaseOrder = {"overnight", "pre_market", "opening_5min", "early_session",
                                "midday", "late_session", "closing_5min"};
        for (String phase : phaseOrder) {
            int n = byPhase.path(phase).asInt(0);
            if (n > 0) {
                sb.append("  ").append(phaseLabel(phase)).append(": ").append(n).append('\n');
            }
        }
        sb.append('\n');

        // SCORER COUNTS for the whole day.
        sb.append("SCORER COUNTS (whole day):\n");
        com.fasterxml.jackson.databind.JsonNode byScorer = dayAggregates.path("by_scorer");
        java.util.Iterator<String> scorerNames = byScorer.fieldNames();
        java.util.List<String> sortedScorers = new java.util.ArrayList<>();
        while (scorerNames.hasNext()) sortedScorers.add(scorerNames.next());
        java.util.Collections.sort(sortedScorers);
        for (String s : sortedScorers) {
            sb.append("  ").append(s).append(": ").append(byScorer.path(s).asInt(0)).append('\n');
        }
        sb.append('\n');

        // TOP SYMBOLS by event count.
        com.fasterxml.jackson.databind.JsonNode topSymbols = dayAggregates.path("top_symbols_by_event_count");
        if (topSymbols.isArray() && topSymbols.size() > 0) {
            sb.append("TOP SYMBOLS BY EVENT COUNT:\n");
            for (com.fasterxml.jackson.databind.JsonNode sym : topSymbols) {
                sb.append("  ").append(sym.path("symbol").asText()).append(": ")
                  .append(sym.path("count").asInt()).append('\n');
            }
            sb.append('\n');
        }

        // Split events into day-level (inter-day signals) and intraday (event-shaped).
        java.util.List<EventRow> dayLevel = new java.util.ArrayList<>();
        java.util.List<EventRow> intraday = new java.util.ArrayList<>();
        for (EventRow e : events) {
            if (INTER_DAY_SCORERS_FOR_SYNTH.contains(e.scorerId)) {
                dayLevel.add(e);
            } else {
                intraday.add(e);
            }
        }

        // DAY-LEVEL SIGNALS (inter-day metrics — no clock anchor).
        if (!dayLevel.isEmpty()) {
            sb.append("DAY-LEVEL SIGNALS (inter-day measurements, no clock anchor — ")
              .append(dayLevel.size()).append(" symbols):\n");
            for (EventRow e : dayLevel) {
                String body = (e.interpretation != null && !e.interpretation.isBlank())
                              ? e.interpretation.trim()
                              : (e.narration != null ? e.narration.trim() : "(no narration)");
                sb.append("  [").append(e.scorerId).append("] ")
                  .append(e.symbol == null ? "—" : e.symbol).append(" — ")
                  .append(body).append('\n');
            }
            sb.append('\n');
        }

        // INTRADAY EVENTS grouped by session phase, score-ordered within each phase.
        sb.append("INTRADAY EVENTS (regular-session story — grouped by phase, ")
          .append(intraday.size()).append(" events):\n\n");
        for (String phase : phaseOrder) {
            // Filter events in this phase + sort by score descending.
            java.util.List<EventRow> phaseEvents = new java.util.ArrayList<>();
            for (EventRow e : intraday) {
                String evtPhase = com.longexposure.scoring.BreakdownFmt.sessionPhase(e.ts.toInstant());
                if (phase.equals(evtPhase)) phaseEvents.add(e);
            }
            if (phaseEvents.isEmpty()) continue;
            phaseEvents.sort((a, b) -> Double.compare(b.score, a.score));
            sb.append("  ").append(phaseLabel(phase)).append(" (").append(phaseEvents.size())
              .append("):\n");
            for (EventRow e : phaseEvents) {
                String body = (e.interpretation != null && !e.interpretation.isBlank())
                              ? e.interpretation.trim()
                              : (e.narration != null ? e.narration.trim() : "(no narration)");
                sb.append("    [").append(e.scorerId).append("] ")
                  .append(e.symbol == null ? "—" : e.symbol).append(" · ")
                  .append(com.longexposure.scoring.BreakdownFmt.toEtTime(e.ts.toInstant()))
                  .append(" ET — ").append(body).append('\n');
            }
            sb.append('\n');
        }

        // Closing instruction. Trust the system prompt for rules; no Constraints
        // duplication block here.
        sb.append("Write the day's themes paragraph now.\n");
        return sb.toString();
    }

    private static final java.util.Set<String> INTER_DAY_SCORERS_FOR_SYNTH =
            java.util.Set.of("volume_deviation", "time_in_book_drift");

    private static String phaseLabel(final String phase) {
        return switch (phase) {
            case "overnight"     -> "Overnight (16:00 prior day - 04:00 ET)";
            case "pre_market"    -> "Pre-market (04:00 - 09:30 ET)";
            case "opening_5min"  -> "Opening 5 min (09:30 - 09:35 ET)";
            case "early_session" -> "Early session (09:35 - 11:30 ET)";
            case "midday"        -> "Midday (11:30 - 15:00 ET)";
            case "late_session"  -> "Late session (15:00 - 15:55 ET)";
            case "closing_5min"  -> "Close (15:55 - 16:00 ET)";
            default              -> phase;
        };
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
                        final ObjectNode dataTable,
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
                    verifier_passed, verifier_notes, data_table
                )
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?::jsonb)
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
                    data_table                 = EXCLUDED.data_table,
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
            st.setString(11, dataTable.toString());
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
        double score;
        String narration;
        String interpretation;
    }
}
