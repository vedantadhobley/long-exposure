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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mirror of {@link AggregateQuarterActivityImpl}, one tier up. Reads the
 * year's ≤4 quarterly rollups + prior yearly rollups. Sits deeply dormant
 * until {@link #MIN_QUARTERS_FOR_YEAR} quarters of data exist.
 */
public final class AggregateYearActivityImpl implements AggregateYearActivity {

    private static final Logger LOG = LoggerFactory.getLogger(AggregateYearActivityImpl.class);

    private static final String MODEL_ID = System.getenv()
            .getOrDefault("LLAMA_MODEL", "Qwen3.5-122B-A10B");

    private static final String PROMPT_VERSION = "aggregate-year-v1-2026-05-28";

    /** Quarterly rollups needed in the year before the LLM call fires. */
    private static final int MIN_QUARTERS_FOR_YEAR = 2;

    /** Prior yearly rollups for multi-year trend context. */
    private static final int PRIOR_YEARS = 2;

    private static final int MAX_LLM_ATTEMPTS = 3;

    private static final String SYSTEM_PROMPT = """
            You are a financial-data journalist writing one PARAGRAPH summarizing
            a full calendar year of IEX-exchange microstructure activity — the
            long-exposure retrospective at its widest aperture.

            INPUTS: metadata + this year's QUARTERLY THEMES (≤4 paragraphs,
            chronological) + any prior YEARLY rollups for trend.

            OUTPUT: 5-9 sentences, journalist register. Hard cap 1200 chars.
            Acronyms glossed on first use. No preamble.

            GROUNDING. Every ticker MUST appear in the inputs. Every number
            MUST appear verbatim in a quarterly paragraph or the metadata.
            Do not compute, sum across quarters, or approximate. If a number
            is not in the inputs, do not mention it.

            REGISTER. Describe what the data shows. NO intent claims, NO
            participant attribution, NO outside news/causal hypotheses, NO
            editorializing.

            STREAK CLAIMS. Bound any streak claim to the prior-yearly count
            + 1. With 0 prior years, do not make year-over-year comparisons.

            FOCUS. A YEAR is the longest horizon — prefer themes that play
            out across multiple quarters: regime shifts that crossed quarter
            boundaries, instruments that dominated the whole year, structural
            evolutions visible only at this scale. Avoid quarter-level detail.
            """;

    @Override
    public long aggregate(final LocalDate anyDateInYear) {
        ActivityExecutionContext actx = Activity.getExecutionContext();
        ObjectMapper json = new ObjectMapper();
        LlamaClient llama = LlamaClient.fromEnv();

        LocalDate yearStart = LocalDate.of(anyDateInYear.getYear(), Month.JANUARY, 1);
        LocalDate yearEndExclusive = yearStart.plusYears(1);

        try (Connection conn = openConnection()) {
            SchemaManager.apply(conn);

            actx.heartbeat("load");
            List<QuarterRow> quarters = loadYear(conn, json, yearStart, yearEndExclusive);
            if (quarters.size() < MIN_QUARTERS_FOR_YEAR) {
                LOG.info("aggregate-year dormant  year_start={} quarters={}/{}",
                        yearStart, quarters.size(), MIN_QUARTERS_FOR_YEAR);
                return 0;
            }
            LocalDate yearEnd = quarters.get(quarters.size() - 1).quarterEnd;

            List<PriorYear> priors = loadPriorYears(conn, yearStart, PRIOR_YEARS);

            byte[] contentHash = contentHash(quarters, priors);
            if (hashUnchanged(conn, yearStart, contentHash)) {
                LOG.info("aggregate-year skip (cached)  year_start={} quarters={} priors={}",
                        yearStart, quarters.size(), priors.size());
                return 1;
            }

            actx.heartbeat("rollup");
            ObjectNode yearAggregates = rollUp(quarters, yearStart, yearEnd, json);

            Set<String> tickers = new HashSet<>();
            StringBuilder haystack = new StringBuilder(32 * 1024);
            GroundingVerifier.appendAllValues(yearAggregates, haystack);
            haystack.append('\n');
            for (QuarterRow q : quarters) {
                tickers.addAll(SynthesisVerifier.extractTickers(q.aggregateText));
                haystack.append(q.aggregateText).append('\n');
            }
            for (PriorYear p : priors) {
                tickers.addAll(SynthesisVerifier.extractTickers(p.aggregateText));
                haystack.append(p.aggregateText).append('\n');
            }

            actx.heartbeat("prompt");
            String userPrompt = buildUserPrompt(yearStart, yearEnd, yearAggregates, quarters, priors);

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
                verify = verifier.verify(aggregate, tickers, haystack.toString());
                int claimedStreak = maxStreakYearsClaimed(aggregate);
                streakOk = claimedStreak <= allowedStreak;
                if (verify.passed() && streakOk) {
                    if (attempt > 1) LOG.info("aggregate-year verifier passed on retry  year_start={} attempt={}",
                            yearStart, attempt);
                    break;
                }
                LOG.warn("aggregate-year verifier failed  year_start={} attempt={}/{} mismatches={} streak_claimed={} allowed={}",
                        yearStart, attempt, MAX_LLM_ATTEMPTS, verify.mismatches(), claimedStreak, allowedStreak);
            }

            actx.heartbeat("upsert");
            upsert(conn, yearStart, yearEnd, aggregate, yearAggregates, quarters.size(),
                    verify, streakOk, allowedStreak, contentHash, json);

            LOG.info("aggregated year  year_start={} year_end={} quarters={} llm_ms={} verifier_passed={}",
                    yearStart, yearEnd, quarters.size(), llmElapsedMs,
                    verify.passed() && streakOk);
            return 1;
        } catch (Exception e) {
            throw new RuntimeException("aggregate-year failed for year_start=" + yearStart, e);
        }
    }

    private List<QuarterRow> loadYear(final Connection conn, final ObjectMapper json,
                                      final LocalDate yearStart, final LocalDate yearEndExclusive)
            throws Exception {
        String sql = """
                SELECT quarter_start, quarter_end, aggregate_text, quarter_aggregates
                FROM quarterly_aggregate
                WHERE quarter_start >= ? AND quarter_start < ?
                  AND verifier_passed = true
                ORDER BY quarter_start
                """;
        List<QuarterRow> out = new ArrayList<>();
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, yearStart);
            st.setObject(2, yearEndExclusive);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    QuarterRow r = new QuarterRow();
                    r.quarterStart    = rs.getObject("quarter_start", LocalDate.class);
                    r.quarterEnd      = rs.getObject("quarter_end", LocalDate.class);
                    r.aggregateText   = rs.getString("aggregate_text");
                    String agg = rs.getString("quarter_aggregates");
                    r.quarterAggregates = (agg == null) ? json.createObjectNode() : json.readTree(agg);
                    out.add(r);
                }
            }
        }
        return out;
    }

    private ObjectNode rollUp(final List<QuarterRow> quarters, final LocalDate yearStart,
                              final LocalDate yearEnd, final ObjectMapper json) {
        long totalEvents = 0;
        java.util.Map<String, Long> byScorer = new java.util.TreeMap<>();
        java.util.Map<String, Long> bySymbol = new java.util.HashMap<>();

        ObjectNode agg = json.createObjectNode();
        agg.put("year_start", yearStart.toString());
        agg.put("year_end", yearEnd.toString());
        agg.put("quarters_considered", quarters.size());

        ArrayNode perQuarter = agg.putArray("per_quarter");
        for (QuarterRow q : quarters) {
            JsonNode qa = q.quarterAggregates;
            long qEvents = qa.path("total_events_quarter").asLong(0);
            totalEvents += qEvents;
            ObjectNode pq = perQuarter.addObject();
            pq.put("quarter_start", q.quarterStart.toString());
            pq.put("events", qEvents);

            JsonNode byScorerQ = qa.path("by_scorer_quarter");
            byScorerQ.fieldNames().forEachRemaining(k ->
                    byScorer.merge(k, byScorerQ.path(k).asLong(0), Long::sum));

            for (JsonNode s : qa.path("top_symbols_quarter")) {
                String sym = s.path("symbol").asText(null);
                if (sym != null) bySymbol.merge(sym, s.path("count").asLong(0), Long::sum);
            }
        }

        agg.put("total_events_year", totalEvents);

        ObjectNode scorerNode = agg.putObject("by_scorer_year");
        for (java.util.Map.Entry<String, Long> e : byScorer.entrySet()) scorerNode.put(e.getKey(), e.getValue());

        List<java.util.Map.Entry<String, Long>> topSyms = new ArrayList<>(bySymbol.entrySet());
        topSyms.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        if (topSyms.size() > 20) topSyms = topSyms.subList(0, 20);
        ArrayNode topSymNode = agg.putArray("top_symbols_year");
        for (java.util.Map.Entry<String, Long> e : topSyms) {
            ObjectNode s = topSymNode.addObject();
            s.put("symbol", e.getKey());
            s.put("count", e.getValue());
        }
        return agg;
    }

    private String buildUserPrompt(final LocalDate yearStart, final LocalDate yearEnd,
                                   final ObjectNode yearAggregates, final List<QuarterRow> quarters,
                                   final List<PriorYear> priors) {
        StringBuilder sb = new StringBuilder(32 * 1024);
        sb.append("Calendar year: ").append(yearStart).append(" to ").append(yearEnd).append("\n\n");
        sb.append("YEAR METADATA: ").append(yearAggregates.toString()).append("\n\n");

        if (priors.isEmpty()) {
            sb.append("PRIOR YEARS: none available — this is the earliest year in the archive. "
                    + "Do NOT make year-over-year comparisons; describe only THIS year.\n\n");
        } else {
            int n = priors.size();
            sb.append("PRIOR YEARS (").append(n).append(" year").append(n == 1 ? "" : "s")
              .append(" of trend context; longest honest streak is ").append(n + 1).append(" years):\n\n");
            for (int i = priors.size() - 1; i >= 0; i--) {
                PriorYear p = priors.get(i);
                sb.append("[year ").append(p.yearStart.getYear()).append("] ")
                  .append(p.aggregateText == null ? "(no aggregate)" : p.aggregateText.trim())
                  .append("\n\n");
            }
        }

        sb.append("THIS YEAR — PER-QUARTER THEMES (chronological, ").append(quarters.size()).append(" quarters):\n\n");
        for (QuarterRow q : quarters) {
            sb.append("[quarter of ").append(q.quarterStart).append("] ")
              .append(q.aggregateText == null ? "(no aggregate)" : q.aggregateText.trim())
              .append("\n\n");
        }
        sb.append("Now write THIS year's retrospective paragraph (5-9 sentences, journalist register, "
                + "synthesize across this year's quarters; prior years frame multi-year trends).");
        return sb.toString();
    }

    private void upsert(final Connection conn, final LocalDate yearStart, final LocalDate yearEnd,
                        final String aggregate, final ObjectNode yearAggregates,
                        final int quartersConsidered, final SynthesisVerifier.Result verify,
                        final boolean streakOk, final int allowedStreak,
                        final byte[] contentHash, final ObjectMapper json) throws Exception {
        boolean passed = verify.passed() && streakOk;
        ObjectNode verifierNotes = json.createObjectNode();
        verifierNotes.put("tickers_checked", verify.tickersChecked());
        verifierNotes.put("numbers_checked", verify.numbersChecked());
        verifierNotes.put("streak_ok", streakOk);
        ArrayNode mismatches = json.valueToTree(verify.mismatches());
        if (!streakOk) {
            mismatches.add("streak claim exceeds the " + allowedStreak + "-year ceiling");
        }
        verifierNotes.set("mismatches", mismatches);

        String sql = """
                INSERT INTO yearly_aggregate (
                    year_start, year_end, aggregate_text, quarters_considered,
                    year_aggregates, model_id, prompt_version,
                    verifier_passed, verifier_notes, content_hash
                )
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (year_start) DO UPDATE SET
                    year_end            = EXCLUDED.year_end,
                    aggregate_text      = EXCLUDED.aggregate_text,
                    quarters_considered = EXCLUDED.quarters_considered,
                    year_aggregates     = EXCLUDED.year_aggregates,
                    model_id            = EXCLUDED.model_id,
                    prompt_version      = EXCLUDED.prompt_version,
                    verifier_passed     = EXCLUDED.verifier_passed,
                    verifier_notes      = EXCLUDED.verifier_notes,
                    content_hash        = EXCLUDED.content_hash,
                    created_at          = NOW()
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, yearStart);
            st.setObject(2, yearEnd);
            st.setString(3, aggregate);
            st.setInt(4, quartersConsidered);
            st.setString(5, yearAggregates.toString());
            st.setString(6, MODEL_ID);
            st.setString(7, PROMPT_VERSION);
            st.setBoolean(8, passed);
            st.setString(9, verifierNotes.toString());
            st.setBytes(10, contentHash);
            st.executeUpdate();
        }
    }

    private List<PriorYear> loadPriorYears(final Connection conn, final LocalDate yearStart,
                                           final int n) throws Exception {
        String sql = """
                SELECT year_start, aggregate_text
                FROM yearly_aggregate
                WHERE year_start < ?
                ORDER BY year_start DESC
                LIMIT ?
                """;
        List<PriorYear> out = new ArrayList<>();
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, yearStart);
            st.setInt(2, n);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    PriorYear p = new PriorYear();
                    p.yearStart     = rs.getObject("year_start", LocalDate.class);
                    p.aggregateText = rs.getString("aggregate_text");
                    out.add(p);
                }
            }
        }
        return out;
    }

    private static byte[] contentHash(final List<QuarterRow> quarters, final List<PriorYear> priors) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(PROMPT_VERSION.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(MODEL_ID.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            for (QuarterRow q : quarters) {
                md.update(q.quarterStart.toString().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update((q.aggregateText == null ? "" : q.aggregateText).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            md.update((byte) 0x1e);
            for (PriorYear p : priors) {
                md.update(p.yearStart.toString().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update((p.aggregateText == null ? "" : p.aggregateText).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException("year content hash failed", e);
        }
    }

    private static boolean hashUnchanged(final Connection conn, final LocalDate yearStart,
                                         final byte[] contentHash) throws Exception {
        String sql = "SELECT 1 FROM yearly_aggregate "
                + "WHERE year_start = ? AND content_hash = ? AND verifier_passed = true LIMIT 1";
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, yearStart);
            st.setBytes(2, contentHash);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static final java.util.regex.Pattern STREAK_RE = java.util.regex.Pattern.compile(
            "\\b(\\d{1,2}(?:st|nd|rd|th)?|first|second|third|fourth|fifth|sixth|seventh)"
            + "\\s+(?:consecutive|straight)\\s+years?\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.Map<String, Integer> ORD_WORDS = java.util.Map.ofEntries(
            java.util.Map.entry("first", 1), java.util.Map.entry("second", 2), java.util.Map.entry("third", 3),
            java.util.Map.entry("fourth", 4), java.util.Map.entry("fifth", 5), java.util.Map.entry("sixth", 6),
            java.util.Map.entry("seventh", 7));

    static int maxStreakYearsClaimed(final String text) {
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

    private static final class QuarterRow {
        LocalDate quarterStart;
        LocalDate quarterEnd;
        String aggregateText;
        JsonNode quarterAggregates;
    }

    private static final class PriorYear {
        LocalDate yearStart;
        String aggregateText;
    }
}
