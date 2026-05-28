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
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mirror of {@link AggregateWeekActivityImpl} one tier up. Reads the
 * quarter's weekly rollups + prior quarterly rollups; writes a single
 * paragraph to {@code quarterly_aggregate}. Sits dormant until at least
 * {@link #MIN_WEEKS_FOR_QUARTER} weekly rollups exist for the quarter.
 *
 * <p>Calendar quarters (not ISO): Q1 = Jan-Mar, Q2 = Apr-Jun, Q3 = Jul-Sep,
 * Q4 = Oct-Dec. The first day of a quarter is its anchor (PK on
 * {@code quarter_start}).
 */
public final class AggregateQuarterActivityImpl implements AggregateQuarterActivity {

    private static final Logger LOG = LoggerFactory.getLogger(AggregateQuarterActivityImpl.class);

    private static final String MODEL_ID = System.getenv()
            .getOrDefault("LLAMA_MODEL", "Qwen3.5-122B-A10B");

    /**
     * v3 (2026-05-28 evening) wires the structural AttributionVerifier at the
     * quarter tier: quarter-aggregated by_symbol_by_scorer + by_symbol_total
     * maps from PeriodAttributionMaps.load(). Mirror of the same fix at the
     * week tier (AggregateWeekActivityImpl v7). v2 added word-form numeral
     * grounding.
     */
    private static final String PROMPT_VERSION = "aggregate-quarter-v3-attribution-verifier-2026-05-28";

    /**
     * Minimum weekly rollups in the quarter before the activity does an LLM
     * call. Below this floor the quarterly narrative would be thin / unreliable
     * (8 weeks ≈ 2 months of weekly themes). Returns 0 (dormant) until met.
     */
    private static final int MIN_WEEKS_FOR_QUARTER = 8;

    /** Prior quarterly rollups passed as quarter-over-quarter trend context. */
    private static final int PRIOR_QUARTERS = 4;

    private static final int MAX_LLM_ATTEMPTS = 3;

    private static final String SYSTEM_PROMPT = """
            You are a financial-data journalist writing one PARAGRAPH summarizing
            a calendar quarter of IEX-exchange microstructure activity. The
            paragraph synthesizes themes across the quarter's weekly rollups —
            persistent regimes, recurring symbols, structural shifts.

            INPUTS: a metadata block + this quarter's WEEKLY THEMES (≤13
            paragraphs, chronological) + any prior QUARTERLY rollups for trend
            context.

            OUTPUT: 4-7 sentences, journalist register. Hard cap 900 chars.
            Acronyms (LULD, VWAP, HFT, NBBO) glossed on first use. No preamble.

            GROUNDING — primary rule. Every ticker MUST appear in the inputs.
            Every number MUST appear verbatim in either a weekly paragraph or
            the metadata block. Do not compute, sum across weeks, or
            approximate. If a number you want to mention is not in the inputs,
            do not mention it.

            REGISTER. Describe what the data shows. Do NOT speculate about
            intent, name participants, or pull in news/causal hypotheses from
            outside the wire data. Do not editorialize ("striking", "unusual").
            The catalog vocabulary (sweep, layering, post-cancel cluster,
            iceberg, liquidity withdrawal, halt, volume surge, time-in-book
            drift) is the pattern-name surface; use it.

            STREAK CLAIMS. When you have N prior quarterly rollups available,
            the longest honest streak you can claim is N+1 quarters. With 0
            prior quarters, do NOT make any quarter-over-quarter comparison.
            With 1 prior quarter, the strongest claim is "a second consecutive
            quarter" — never "third / fourth / fifth …" which would invent
            quarters you cannot see.

            FOCUS. A QUARTER is a long horizon. Prefer themes that are
            QUARTER-shaped: persistent regimes (e.g., "leveraged-ETF dominance
            held across all 13 weeks"), structural shifts (a quiet first half
            vs an active second half), recurring symbol clusters across many
            weeks, breadth (how many distinct tickers traded patterns vs how
            concentrated). Avoid week-or-day-level detail — those belong in
            the underlying weekly paragraphs.
            """;

    @Override
    public long aggregate(final LocalDate anyDateInQuarter) {
        ActivityExecutionContext actx = Activity.getExecutionContext();
        ObjectMapper json = new ObjectMapper();
        LlamaClient llama = LlamaClient.fromEnv();

        LocalDate quarterStart = quarterStartOf(anyDateInQuarter);
        LocalDate quarterEndExclusive = quarterStart.plusMonths(3);

        try (Connection conn = openConnection()) {
            SchemaManager.apply(conn);

            actx.heartbeat("load");
            List<WeekRow> weeks = loadQuarter(conn, json, quarterStart, quarterEndExclusive);
            if (weeks.size() < MIN_WEEKS_FOR_QUARTER) {
                LOG.info("aggregate-quarter dormant  quarter_start={} weeks={}/{}",
                        quarterStart, weeks.size(), MIN_WEEKS_FOR_QUARTER);
                return 0;
            }
            LocalDate quarterEnd = weeks.get(weeks.size() - 1).weekEnd;

            List<PriorQuarter> priors = loadPriorQuarters(conn, quarterStart, PRIOR_QUARTERS);

            byte[] contentHash = contentHash(weeks, priors);
            if (hashUnchanged(conn, quarterStart, contentHash)) {
                LOG.info("aggregate-quarter skip (cached)  quarter_start={} weeks={} priors={}",
                        quarterStart, weeks.size(), priors.size());
                return 1;
            }

            actx.heartbeat("rollup");
            ObjectNode quarterAggregates = rollUp(weeks, quarterStart, quarterEnd, json);

            Set<String> tickers = new HashSet<>();
            StringBuilder haystack = new StringBuilder(32 * 1024);
            GroundingVerifier.appendAllValues(quarterAggregates, haystack);
            haystack.append('\n');
            for (WeekRow w : weeks) {
                tickers.addAll(SynthesisVerifier.extractTickers(w.aggregateText));
                haystack.append(w.aggregateText).append('\n');
            }
            for (PriorQuarter p : priors) {
                tickers.addAll(SynthesisVerifier.extractTickers(p.aggregateText));
                haystack.append(p.aggregateText).append('\n');
            }

            // Quarter-aggregated attribution truth maps — same shape as the
            // week-tier maps in AggregateWeekActivityImpl.loadWeekTruthMaps,
            // summed across the quarter's ~65 trading days. Used by
            // AttributionVerifier (via SynthesisVerifier 5-arg verify) to
            // catch quarter-scope misattribution like "TQQQ had X events this
            // quarter" against actual cross-day sums. Period-level mirror of
            // R5 from earlier this evening.
            actx.heartbeat("attribution_maps");
            Map<String, Map<String, Integer>> quarterBySymbolByScorer = new HashMap<>();
            Map<String, Integer> quarterBySymbolTotal = new HashMap<>();
            PeriodAttributionMaps.load(conn, quarterStart, quarterEnd.plusDays(1),
                    quarterBySymbolByScorer, quarterBySymbolTotal);

            actx.heartbeat("prompt");
            String userPrompt = buildUserPrompt(quarterStart, quarterEnd, quarterAggregates, weeks, priors);

            SynthesisVerifier verifier = new SynthesisVerifier();
            int allowedStreak = priors.size() + 1;

            actx.heartbeat("llm");
            String aggregate = null;
            SynthesisVerifier.Result verify = null;
            boolean streakOk = true;
            long llmElapsedMs = 0;
            for (int attempt = 1; attempt <= MAX_LLM_ATTEMPTS; attempt++) {
                long llmT0 = System.nanoTime();
                aggregate = llama.chat(SYSTEM_PROMPT, userPrompt, SamplingParams.AGGREGATE).trim();
                llmElapsedMs = (System.nanoTime() - llmT0) / 1_000_000L;
                verify = verifier.verify(aggregate, tickers, haystack.toString(),
                        quarterBySymbolByScorer, quarterBySymbolTotal);
                int claimedStreak = maxStreakQuartersClaimed(aggregate);
                streakOk = claimedStreak <= allowedStreak;
                if (verify.passed() && streakOk) {
                    if (attempt > 1) LOG.info("aggregate-quarter verifier passed on retry  quarter_start={} attempt={}",
                            quarterStart, attempt);
                    break;
                }
                LOG.warn("aggregate-quarter verifier failed  quarter_start={} attempt={}/{} mismatches={} streak_claimed={} allowed={}",
                        quarterStart, attempt, MAX_LLM_ATTEMPTS, verify.mismatches(), claimedStreak, allowedStreak);
            }

            actx.heartbeat("upsert");
            upsert(conn, quarterStart, quarterEnd, aggregate, quarterAggregates, weeks.size(),
                    verify, streakOk, allowedStreak, contentHash, json);

            LOG.info("aggregated quarter  quarter_start={} quarter_end={} weeks={} llm_ms={} verifier_passed={}",
                    quarterStart, quarterEnd, weeks.size(), llmElapsedMs,
                    verify.passed() && streakOk);
            return 1;
        } catch (Exception e) {
            throw new RuntimeException("aggregate-quarter failed for quarter_start=" + quarterStart, e);
        }
    }

    /** First day of the calendar quarter containing {@code d}. */
    static LocalDate quarterStartOf(final LocalDate d) {
        int qMonth = ((d.getMonthValue() - 1) / 3) * 3 + 1;   // 1, 4, 7, 10
        return LocalDate.of(d.getYear(), Month.of(qMonth), 1);
    }

    private List<WeekRow> loadQuarter(final Connection conn, final ObjectMapper json,
                                      final LocalDate quarterStart, final LocalDate quarterEndExclusive)
            throws Exception {
        String sql = """
                SELECT week_start, week_end, aggregate_text, week_aggregates
                FROM weekly_aggregate
                WHERE week_start >= ? AND week_start < ?
                  AND verifier_passed = true
                ORDER BY week_start
                """;
        List<WeekRow> out = new ArrayList<>();
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, quarterStart);
            st.setObject(2, quarterEndExclusive);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    WeekRow r = new WeekRow();
                    r.weekStart      = rs.getObject("week_start", LocalDate.class);
                    r.weekEnd        = rs.getObject("week_end", LocalDate.class);
                    r.aggregateText  = rs.getString("aggregate_text");
                    String agg = rs.getString("week_aggregates");
                    r.weekAggregates = (agg == null) ? json.createObjectNode() : json.readTree(agg);
                    out.add(r);
                }
            }
        }
        return out;
    }

    /** Sum the per-week rollups into a quarter-level view. */
    private ObjectNode rollUp(final List<WeekRow> weeks, final LocalDate quarterStart,
                              final LocalDate quarterEnd, final ObjectMapper json) {
        long totalEvents = 0;
        java.util.Map<String, Long> byScorer = new java.util.TreeMap<>();
        java.util.Map<String, Long> bySymbol = new java.util.HashMap<>();

        ObjectNode agg = json.createObjectNode();
        agg.put("quarter_start", quarterStart.toString());
        agg.put("quarter_end", quarterEnd.toString());
        agg.put("weeks_considered", weeks.size());

        ArrayNode perWeek = agg.putArray("per_week");
        for (WeekRow w : weeks) {
            JsonNode wa = w.weekAggregates;
            long weekEvents = wa.path("total_events_week").asLong(0);
            totalEvents += weekEvents;
            ObjectNode pw = perWeek.addObject();
            pw.put("week_start", w.weekStart.toString());
            pw.put("events", weekEvents);

            JsonNode byScorerWeek = wa.path("by_scorer_week");
            byScorerWeek.fieldNames().forEachRemaining(k ->
                    byScorer.merge(k, byScorerWeek.path(k).asLong(0), Long::sum));

            for (JsonNode s : wa.path("top_symbols_week")) {
                String sym = s.path("symbol").asText(null);
                if (sym != null) bySymbol.merge(sym, s.path("count").asLong(0), Long::sum);
            }
        }

        agg.put("total_events_quarter", totalEvents);

        ObjectNode scorerNode = agg.putObject("by_scorer_quarter");
        for (java.util.Map.Entry<String, Long> e : byScorer.entrySet()) scorerNode.put(e.getKey(), e.getValue());

        List<java.util.Map.Entry<String, Long>> topSyms = new ArrayList<>(bySymbol.entrySet());
        topSyms.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        if (topSyms.size() > 15) topSyms = topSyms.subList(0, 15);
        ArrayNode topSymNode = agg.putArray("top_symbols_quarter");
        for (java.util.Map.Entry<String, Long> e : topSyms) {
            ObjectNode s = topSymNode.addObject();
            s.put("symbol", e.getKey());
            s.put("count", e.getValue());
        }
        return agg;
    }

    private String buildUserPrompt(final LocalDate quarterStart, final LocalDate quarterEnd,
                                   final ObjectNode quarterAggregates, final List<WeekRow> weeks,
                                   final List<PriorQuarter> priors) {
        StringBuilder sb = new StringBuilder(32 * 1024);
        sb.append("Calendar quarter: ").append(quarterStart).append(" to ").append(quarterEnd).append("\n\n");
        sb.append("QUARTER METADATA: ").append(quarterAggregates.toString()).append("\n\n");

        if (priors.isEmpty()) {
            sb.append("PRIOR QUARTERS: none available — this is the earliest quarter in the archive. "
                    + "Do NOT make any quarter-over-quarter comparison or reference earlier quarters, "
                    + "streaks, or trends; describe only THIS quarter.\n\n");
        } else {
            int n = priors.size();
            sb.append("PRIOR QUARTERS (").append(n).append(" quarter").append(n == 1 ? "" : "s")
              .append(" of trend context — the ONLY earlier quarters you know about; ")
              .append("the longest honest streak is ").append(n + 1).append(" quarters; ")
              .append("do NOT present these as this quarter's activity):\n\n");
            for (int i = priors.size() - 1; i >= 0; i--) {
                PriorQuarter p = priors.get(i);
                sb.append("[quarter of ").append(p.quarterStart).append("] ")
                  .append(p.aggregateText == null ? "(no aggregate)" : p.aggregateText.trim())
                  .append("\n\n");
            }
        }

        sb.append("THIS QUARTER — PER-WEEK THEMES (chronological, ").append(weeks.size()).append(" weeks):\n\n");
        for (WeekRow w : weeks) {
            sb.append("[week of ").append(w.weekStart).append("] ")
              .append(w.aggregateText == null ? "(no aggregate)" : w.aggregateText.trim())
              .append("\n\n");
        }
        sb.append("Now write THIS quarter's themes paragraph (4-7 sentences, journalist register, "
                + "synthesize across this quarter's weeks; the prior quarters frame quarter-over-quarter "
                + "trends — do not summarize them as part of this quarter).");
        return sb.toString();
    }

    private void upsert(final Connection conn, final LocalDate quarterStart, final LocalDate quarterEnd,
                        final String aggregate, final ObjectNode quarterAggregates,
                        final int weeksConsidered, final SynthesisVerifier.Result verify,
                        final boolean streakOk, final int allowedStreak,
                        final byte[] contentHash, final ObjectMapper json) throws Exception {
        boolean passed = verify.passed() && streakOk;
        ObjectNode verifierNotes = json.createObjectNode();
        verifierNotes.put("tickers_checked", verify.tickersChecked());
        verifierNotes.put("numbers_checked", verify.numbersChecked());
        verifierNotes.put("streak_ok", streakOk);
        ArrayNode mismatches = json.valueToTree(verify.mismatches());
        if (!streakOk) {
            mismatches.add("streak claim exceeds the " + allowedStreak
                    + "-quarter ceiling (prior quarters + 1)");
        }
        verifierNotes.set("mismatches", mismatches);

        String sql = """
                INSERT INTO quarterly_aggregate (
                    quarter_start, quarter_end, aggregate_text, weeks_considered,
                    quarter_aggregates, model_id, prompt_version,
                    verifier_passed, verifier_notes, content_hash
                )
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (quarter_start) DO UPDATE SET
                    quarter_end        = EXCLUDED.quarter_end,
                    aggregate_text     = EXCLUDED.aggregate_text,
                    weeks_considered   = EXCLUDED.weeks_considered,
                    quarter_aggregates = EXCLUDED.quarter_aggregates,
                    model_id           = EXCLUDED.model_id,
                    prompt_version     = EXCLUDED.prompt_version,
                    verifier_passed    = EXCLUDED.verifier_passed,
                    verifier_notes     = EXCLUDED.verifier_notes,
                    content_hash       = EXCLUDED.content_hash,
                    created_at         = NOW()
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, quarterStart);
            st.setObject(2, quarterEnd);
            st.setString(3, aggregate);
            st.setInt(4, weeksConsidered);
            st.setString(5, quarterAggregates.toString());
            st.setString(6, MODEL_ID);
            st.setString(7, PROMPT_VERSION);
            st.setBoolean(8, passed);
            st.setString(9, verifierNotes.toString());
            st.setBytes(10, contentHash);
            st.executeUpdate();
        }
    }

    private List<PriorQuarter> loadPriorQuarters(final Connection conn, final LocalDate quarterStart,
                                                 final int n) throws Exception {
        String sql = """
                SELECT quarter_start, aggregate_text
                FROM quarterly_aggregate
                WHERE quarter_start < ?
                ORDER BY quarter_start DESC
                LIMIT ?
                """;
        List<PriorQuarter> out = new ArrayList<>();
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, quarterStart);
            st.setInt(2, n);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    PriorQuarter p = new PriorQuarter();
                    p.quarterStart  = rs.getObject("quarter_start", LocalDate.class);
                    p.aggregateText = rs.getString("aggregate_text");
                    out.add(p);
                }
            }
        }
        return out;
    }

    private static byte[] contentHash(final List<WeekRow> weeks, final List<PriorQuarter> priors) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(PROMPT_VERSION.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(MODEL_ID.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            for (WeekRow w : weeks) {
                md.update(w.weekStart.toString().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update((w.aggregateText == null ? "" : w.aggregateText).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            md.update((byte) 0x1e);
            for (PriorQuarter p : priors) {
                md.update(p.quarterStart.toString().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update((p.aggregateText == null ? "" : p.aggregateText).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException("quarter content hash failed", e);
        }
    }

    private static boolean hashUnchanged(final Connection conn, final LocalDate quarterStart,
                                         final byte[] contentHash) throws Exception {
        String sql = "SELECT 1 FROM quarterly_aggregate "
                + "WHERE quarter_start = ? AND content_hash = ? AND verifier_passed = true LIMIT 1";
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, quarterStart);
            st.setBytes(2, contentHash);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static final java.util.regex.Pattern STREAK_RE = java.util.regex.Pattern.compile(
            "\\b(\\d{1,2}(?:st|nd|rd|th)?|first|second|third|fourth|fifth|sixth|seventh|eighth)"
            + "\\s+(?:consecutive|straight)\\s+quarters?\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.Map<String, Integer> ORD_WORDS = java.util.Map.ofEntries(
            java.util.Map.entry("first", 1), java.util.Map.entry("second", 2), java.util.Map.entry("third", 3),
            java.util.Map.entry("fourth", 4), java.util.Map.entry("fifth", 5), java.util.Map.entry("sixth", 6),
            java.util.Map.entry("seventh", 7), java.util.Map.entry("eighth", 8));

    static int maxStreakQuartersClaimed(final String text) {
        if (text == null) return 0;
        int max = 0;
        java.util.regex.Matcher m = STREAK_RE.matcher(text);
        while (m.find()) {
            String g = m.group(1).toLowerCase();
            Integer n = ORD_WORDS.get(g);
            if (n == null) {
                try { n = Integer.parseInt(g.replaceAll("(st|nd|rd|th)$", "")); }
                catch (NumberFormatException e) { n = 0; }
            }
            max = Math.max(max, n);
        }
        return max;
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

    private static final class WeekRow {
        LocalDate weekStart;
        LocalDate weekEnd;
        String aggregateText;
        JsonNode weekAggregates;
    }

    private static final class PriorQuarter {
        LocalDate quarterStart;
        String aggregateText;
    }
}
