package com.longexposure.temporal.activities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.longexposure.llm.LlamaClient;
import com.longexposure.llm.SamplingParams;
import com.longexposure.narration.GroundingVerifier;
import com.longexposure.narration.SynthesisVerifier;
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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * AGGREGATE implementation — one LLM call per week. See {@link AggregateWeekActivity}
 * for the contract.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Resolve the ISO week of {@code anyDateInWeek} (Monday .. Sunday)
 *   <li>Load that week's {@code daily_synthesis} rows (text + day_aggregates)
 *   <li>Roll the per-day aggregates up to the week: total events, per-scorer
 *       totals, top symbols across the week, per-day event counts
 *   <li>Build the prompt: weekly rules + week metadata + per-day theme paragraphs
 *   <li>One LLM call with {@link SamplingParams#AGGREGATE}
 *   <li>Verify with {@link SynthesisVerifier} — the allowed-ticker universe is
 *       the union of tickers across the week's daily syntheses; the number
 *       haystack is the daily syntheses + week aggregates
 *   <li>Upsert into {@code weekly_aggregate} (PK = week_start)
 * </ol>
 */
public final class AggregateWeekActivityImpl implements AggregateWeekActivity {

    private static final Logger LOG = LoggerFactory.getLogger(AggregateWeekActivityImpl.class);

    private static final String MODEL_ID = System.getenv()
            .getOrDefault("LLAMA_MODEL", "Qwen3.5-122B-A10B");

    /** Bumped when the prompt changes. */
    private static final String PROMPT_VERSION = "aggregate-v4-no-priors-guard-2026-05-26";

    /** Prior weekly rollups passed as week-over-week trend context (§4.3). Tunable. */
    private static final int PRIOR_WEEKS = 8;

    /** Max LLM attempts per week — re-roll on verifier failure (temp 1.0 gives variance). */
    private static final int MAX_LLM_ATTEMPTS = 3;

    private static final String SYSTEM_PROMPT = """
            You are a financial-data journalist writing one paragraph identifying
            the recurring themes of a single US-equity trading WEEK on IEX. Your
            output is the top-of-page summary for that week's market-microstructure
            feed — the level above the per-day summaries.

            INPUTS:
              - WEEK METADATA: week start/end, days covered, total events, per-scorer
                totals across the week, top symbols by event count across the week,
                and per-day event counts.
              - PER-DAY LIST: each trading day's already-written themes paragraph,
                in chronological order, labeled by date. THIS is the substance of
                your paragraph.
              - PRIOR WEEKS (trend context): up to a few preceding weeks' themes
                paragraphs. Use these ONLY to frame week-over-week trends ("a third
                straight week of rising halt activity", "the layering that dominated
                last week faded"). Do NOT present a prior week's tickers or numbers
                as if they happened THIS week.

            OUTPUT: one paragraph (3-6 sentences, ~450-750 chars). Financial-journalist
            register (FT / Bloomberg). Acronyms (LULD, NBBO, MCB, VWAP, HFT) glossed
            on first use. No preamble — lead with the most notable week-level theme.

            WHAT THIS PARAGRAPH IS FOR:

            Identify patterns that span DAYS, which no single day's summary can show:
            symbols or sectors recurring across multiple sessions ("TQQQ and IWM saw
            liquidity withdrawals every day this week"), regimes building or fading
            over the week ("layering activity intensified Monday-to-Wednesday then
            subsided"), session-phase drift across days, and the single most notable
            day or event of the week worth surfacing by name. Synthesize across days
            — do NOT just restate one day's paragraph.

            GROUNDING — the primary rule:

            Every ticker you mention must appear in the per-day paragraphs above.
            Every numeric claim must trace to either the week metadata or a specific
            per-day paragraph — no introducing numbers from outside the inputs, no
            approximation or rounding. Cite numbers VERBATIM as they appear; do NOT
            sum, subtract, or otherwise combine two numbers into a new one (e.g. do
            not add two symbols' event counts together — that derived total is not
            in the data). The week's dates are in the metadata; do not invent or
            restate them in a different format.

            REGISTER:

            You have no news source: the data shows wire activity, not its cause.
            Do not claim external events ("the Fed", "earnings", "geopolitics"). Do
            not speculate about intent. Do not editorialize about severity.
            Week-over-week comparison IS welcome when grounded in the PRIOR WEEKS
            block (e.g. "the third straight week of …"); just don't reach beyond
            the prior-week paragraphs provided.
            """;

    @Override
    public long aggregate(final LocalDate anyDateInWeek) {
        ActivityExecutionContext actx = Activity.getExecutionContext();
        ObjectMapper json = new ObjectMapper();
        LlamaClient llama = LlamaClient.fromEnv();

        LocalDate weekStart = anyDateInWeek.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEndExclusive = weekStart.plusDays(7);   // Mon .. Sun inclusive

        try (Connection conn = openConnection()) {
            SchemaManager.apply(conn);

            actx.heartbeat("load");
            // Stateless rebuild: read THIS week's daily syntheses fresh every
            // run. We never read this week's own prior aggregate_text (that
            // would compound LLM drift across the daily recompute). The
            // "week-so-far" trend instead comes from the PRECEDING weeks, which
            // are already-finalized and don't change underneath us.
            List<DayRow> days = loadWeek(conn, json, weekStart, weekEndExclusive);
            if (days.isEmpty()) {
                LOG.warn("no daily syntheses for week — skipping aggregate  week_start={}", weekStart);
                return 0;
            }
            LocalDate weekEnd = days.get(days.size() - 1).tradingDate;

            // Prior finalized weeks, newest-first, for week-over-week trend context.
            List<PriorWeek> priors = loadPriorWeeks(conn, weekStart, PRIOR_WEEKS);

            // Content-addressed recompute skip. The hash covers everything that
            // can change the output: prompt/model version, this week's day
            // paragraphs (which grow as the week fills in, and change if a day
            // is re-synthesized), and the prior weeks' paragraphs. If a verified
            // row already carries this exact hash, the LLM call is redundant.
            byte[] contentHash = contentHash(days, priors);
            if (hashUnchanged(conn, weekStart, contentHash)) {
                LOG.info("aggregate skip (cached)  week_start={} days={} priors={}",
                        weekStart, days.size(), priors.size());
                return 1;
            }

            actx.heartbeat("rollup");
            ObjectNode weekAggregates = rollUp(days, weekStart, weekEnd, json);

            // Allowed-ticker universe + number haystack come from the daily
            // syntheses themselves (each was already verified ticker-clean
            // against its own day) PLUS the prior weeks' paragraphs (so a
            // grounded week-over-week comparison naming last week's ticker
            // passes the verifier). Reusing SynthesisVerifier.extractTickers
            // keeps the dotted-ticker handling identical to SYNTHESIZE.
            Set<String> weekTickers = new HashSet<>();
            StringBuilder haystack = new StringBuilder(16 * 1024);
            GroundingVerifier.appendAllValues(weekAggregates, haystack);
            haystack.append('\n');
            for (DayRow d : days) {
                weekTickers.addAll(SynthesisVerifier.extractTickers(d.synthesisText));
                haystack.append(d.synthesisText).append('\n');
            }
            for (PriorWeek p : priors) {
                weekTickers.addAll(SynthesisVerifier.extractTickers(p.aggregateText));
                haystack.append(p.aggregateText).append('\n');
            }

            actx.heartbeat("prompt");
            String userPrompt = buildUserPrompt(weekStart, weekEnd, weekAggregates, days, priors);

            SynthesisVerifier verifier = new SynthesisVerifier();

            // Verifier-driven retry: re-roll on a rejected rollup (a derived /
            // cross-week-summed number, a mis-tokenized ticker) — AGGREGATE
            // runs at temp 1.0, so a re-roll usually grounds. Keep first
            // passing, else last.
            actx.heartbeat("llm");
            String aggregate = null;
            SynthesisVerifier.Result verify = null;
            long llmElapsedMs = 0;
            for (int attempt = 1; attempt <= MAX_LLM_ATTEMPTS; attempt++) {
                long llmT0 = System.nanoTime();
                aggregate = llama.chat(SYSTEM_PROMPT, userPrompt, SamplingParams.AGGREGATE).trim();
                llmElapsedMs = (System.nanoTime() - llmT0) / 1_000_000L;
                verify = verifier.verify(aggregate, weekTickers, haystack.toString());
                if (verify.passed()) {
                    if (attempt > 1) LOG.info("aggregate verifier passed on retry  week_start={} attempt={}", weekStart, attempt);
                    break;
                }
                LOG.warn("aggregate verifier failed  week_start={} attempt={}/{} mismatches={}",
                        weekStart, attempt, MAX_LLM_ATTEMPTS, verify.mismatches());
            }

            actx.heartbeat("upsert");
            upsert(conn, weekStart, weekEnd, aggregate, weekAggregates, days.size(),
                    verify, contentHash, json);

            LOG.info("aggregated  week_start={} week_end={} days={} llm_ms={} verifier_passed={} mismatches={}",
                    weekStart, weekEnd, days.size(), llmElapsedMs,
                    verify.passed(), verify.mismatches().size());
            return 1;
        } catch (Exception e) {
            throw new RuntimeException("aggregate failed for week_start=" + weekStart, e);
        }
    }

    /** Daily syntheses for the ISO week, chronological. */
    private List<DayRow> loadWeek(final Connection conn, final ObjectMapper json,
                                  final LocalDate weekStart, final LocalDate weekEndExclusive) throws Exception {
        String sql = """
                SELECT trading_date, synthesis_text, day_aggregates
                FROM daily_synthesis
                WHERE trading_date >= ? AND trading_date < ?
                ORDER BY trading_date
                """;
        List<DayRow> out = new ArrayList<>();
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, weekStart);
            st.setObject(2, weekEndExclusive);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    DayRow r = new DayRow();
                    r.tradingDate   = rs.getObject("trading_date", LocalDate.class);
                    r.synthesisText = rs.getString("synthesis_text");
                    String agg = rs.getString("day_aggregates");
                    r.dayAggregates = (agg == null) ? json.createObjectNode() : json.readTree(agg);
                    out.add(r);
                }
            }
        }
        return out;
    }

    /** Sum the per-day aggregates into a week-level view. */
    private ObjectNode rollUp(final List<DayRow> days, final LocalDate weekStart,
                              final LocalDate weekEnd, final ObjectMapper json) {
        long totalEvents = 0;
        Map<String, Long> byScorer = new TreeMap<>();
        Map<String, Long> bySymbol = new HashMap<>();

        ObjectNode agg = json.createObjectNode();
        agg.put("week_start", weekStart.toString());
        agg.put("week_end", weekEnd.toString());
        agg.put("days_considered", days.size());

        ArrayNode perDay = agg.putArray("per_day");
        for (DayRow d : days) {
            JsonNode da = d.dayAggregates;
            long dayEvents = da.path("total_events").asLong(0);
            totalEvents += dayEvents;
            ObjectNode pd = perDay.addObject();
            pd.put("date", d.tradingDate.toString());
            pd.put("events", dayEvents);

            JsonNode byScorerDay = da.path("by_scorer");
            byScorerDay.fieldNames().forEachRemaining(k ->
                    byScorer.merge(k, byScorerDay.path(k).asLong(0), Long::sum));

            for (JsonNode s : da.path("top_symbols_by_event_count")) {
                String sym = s.path("symbol").asText(null);
                if (sym != null) bySymbol.merge(sym, s.path("count").asLong(0), Long::sum);
            }
        }

        agg.put("total_events_week", totalEvents);

        ObjectNode scorerNode = agg.putObject("by_scorer_week");
        for (Map.Entry<String, Long> e : byScorer.entrySet()) scorerNode.put(e.getKey(), e.getValue());

        // Top symbols across the week by summed event count (top 12).
        List<Map.Entry<String, Long>> topSyms = new ArrayList<>(bySymbol.entrySet());
        topSyms.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        if (topSyms.size() > 12) topSyms = topSyms.subList(0, 12);
        ArrayNode topSymNode = agg.putArray("top_symbols_week");
        for (Map.Entry<String, Long> e : topSyms) {
            ObjectNode s = topSymNode.addObject();
            s.put("symbol", e.getKey());
            s.put("count", e.getValue());
        }
        return agg;
    }

    private String buildUserPrompt(final LocalDate weekStart, final LocalDate weekEnd,
                                   final ObjectNode weekAggregates, final List<DayRow> days,
                                   final List<PriorWeek> priors) {
        StringBuilder sb = new StringBuilder(16 * 1024);
        sb.append("Trading week: ").append(weekStart).append(" to ").append(weekEnd).append("\n\n");
        sb.append("WEEK METADATA: ").append(weekAggregates.toString()).append("\n\n");

        if (priors.isEmpty()) {
            // Be explicit: with no prior weeks, week-over-week phrasing has no
            // ground to stand on. Silently omitting the block let the model
            // invent "a three-week streak noted in prior summaries" from nothing.
            sb.append("PRIOR WEEKS: none available — this is the earliest week in the archive. "
                    + "Do NOT make any week-over-week comparison or reference earlier weeks, "
                    + "streaks, or trends; describe only THIS week.\n\n");
        } else {
            // Chronological (oldest-first) reads more naturally as a trend run-up.
            sb.append("PRIOR WEEKS (trend context only — do NOT present these as this week's activity):\n\n");
            for (int i = priors.size() - 1; i >= 0; i--) {
                PriorWeek p = priors.get(i);
                sb.append("[week of ").append(p.weekStart).append("] ")
                  .append(p.aggregateText == null ? "(no aggregate)" : p.aggregateText.trim())
                  .append("\n\n");
            }
        }

        sb.append("THIS WEEK — PER-DAY THEMES (chronological, ").append(days.size()).append(" days):\n\n");
        for (DayRow d : days) {
            sb.append("[").append(d.tradingDate).append("] ")
              .append(d.synthesisText == null ? "(no synthesis)" : d.synthesisText.trim())
              .append("\n\n");
        }
        sb.append("Now write THIS week's themes paragraph (3-6 sentences, journalist register, "
                + "synthesize across this week's days, ground every claim in the data above; "
                + "use the prior weeks only to frame week-over-week trends).");
        return sb.toString();
    }

    private void upsert(final Connection conn, final LocalDate weekStart, final LocalDate weekEnd,
                        final String aggregate, final ObjectNode weekAggregates,
                        final int daysConsidered, final SynthesisVerifier.Result verify,
                        final byte[] contentHash, final ObjectMapper json) throws Exception {
        ObjectNode verifierNotes = json.createObjectNode();
        verifierNotes.put("tickers_checked", verify.tickersChecked());
        verifierNotes.put("numbers_checked", verify.numbersChecked());
        verifierNotes.set("mismatches", json.valueToTree(verify.mismatches()));

        String sql = """
                INSERT INTO weekly_aggregate (
                    week_start, week_end, aggregate_text, days_considered,
                    week_aggregates, model_id, prompt_version,
                    verifier_passed, verifier_notes, content_hash
                )
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (week_start) DO UPDATE SET
                    week_end        = EXCLUDED.week_end,
                    aggregate_text  = EXCLUDED.aggregate_text,
                    days_considered = EXCLUDED.days_considered,
                    week_aggregates = EXCLUDED.week_aggregates,
                    model_id        = EXCLUDED.model_id,
                    prompt_version  = EXCLUDED.prompt_version,
                    verifier_passed = EXCLUDED.verifier_passed,
                    verifier_notes  = EXCLUDED.verifier_notes,
                    content_hash    = EXCLUDED.content_hash,
                    created_at      = NOW()
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, weekStart);
            st.setObject(2, weekEnd);
            st.setString(3, aggregate);
            st.setInt(4, daysConsidered);
            st.setString(5, weekAggregates.toString());
            st.setString(6, MODEL_ID);
            st.setString(7, PROMPT_VERSION);
            st.setBoolean(8, verify.passed());
            st.setString(9, verifierNotes.toString());
            st.setBytes(10, contentHash);
            st.executeUpdate();
        }
    }

    /** Preceding finalized weekly rollups, newest-first, capped at {@code n}. */
    private List<PriorWeek> loadPriorWeeks(final Connection conn, final LocalDate weekStart,
                                           final int n) throws Exception {
        String sql = """
                SELECT week_start, aggregate_text
                FROM weekly_aggregate
                WHERE week_start < ?
                ORDER BY week_start DESC
                LIMIT ?
                """;
        List<PriorWeek> out = new ArrayList<>();
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, weekStart);
            st.setInt(2, n);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    PriorWeek p = new PriorWeek();
                    p.weekStart     = rs.getObject("week_start", LocalDate.class);
                    p.aggregateText = rs.getString("aggregate_text");
                    out.add(p);
                }
            }
        }
        return out;
    }

    /**
     * SHA-256 over everything that determines the output: prompt + model
     * version, this week's day paragraphs (date + text), and the prior weeks'
     * paragraphs (week_start + text). Deterministic ordering — days are already
     * chronological, priors are newest-first from the query.
     */
    private static byte[] contentHash(final List<DayRow> days, final List<PriorWeek> priors) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(PROMPT_VERSION.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(MODEL_ID.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            for (DayRow d : days) {
                md.update(d.tradingDate.toString().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update((d.synthesisText == null ? "" : d.synthesisText).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            md.update((byte) 0x1e);   // record separator between the two lists
            for (PriorWeek p : priors) {
                md.update(p.weekStart.toString().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update((p.aggregateText == null ? "" : p.aggregateText).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException("content hash failed", e);
        }
    }

    /** True if a verified row for this week already carries this exact content hash. */
    private static boolean hashUnchanged(final Connection conn, final LocalDate weekStart,
                                         final byte[] contentHash) throws Exception {
        String sql = "SELECT 1 FROM weekly_aggregate "
                + "WHERE week_start = ? AND content_hash = ? AND verifier_passed = true LIMIT 1";
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, weekStart);
            st.setBytes(2, contentHash);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next();
            }
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

    private static final class DayRow {
        LocalDate tradingDate;
        String synthesisText;
        JsonNode dayAggregates;
    }

    private static final class PriorWeek {
        LocalDate weekStart;
        String aggregateText;
    }
}
