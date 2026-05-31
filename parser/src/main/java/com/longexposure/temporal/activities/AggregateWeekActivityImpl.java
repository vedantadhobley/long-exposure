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

    /**
     * Bumped when the prompt changes — and on verifier changes that invalidate
     * prior verdicts. v7 (2026-05-28 evening) wires the structural
     * AttributionVerifier into the weekly rollup tier: week-aggregated
     * by_symbol_by_scorer + by_symbol_total maps loaded from selected_events
     * and passed to the new 5-arg SynthesisVerifier.verify(). Closes the
     * misattribution hole at the week level (mirror of the v7 fix that
     * landed for SYNTHESIZE earlier this evening). Prompt unchanged from v6.
     *
     * <p>v6 (earlier 2026-05-28) extended grounding to cardinal word-form
     * numerals; see {@link SynthesizeDayActivityImpl}'s PROMPT_VERSION
     * comment for the full rationale.
     */
    private static final String PROMPT_VERSION = "aggregate-v12-holistic-refactor-2026-05-31";

    /**
     * Prior weekly rollups passed as week-over-week trend context. Set to 13 =
     * one full calendar quarter, so the weekly trend horizon naturally matches
     * the quarterly tier above it (per `tiered-baselines-design.md` §8.1).
     * No runtime change until ≥9 prior weeks exist in the dataset; future-
     * proofs the widening that §8 calls for.
     */
    private static final int PRIOR_WEEKS = 13;

    /** Max LLM attempts per week — re-roll on verifier failure (temp 1.0 gives variance). */
    private static final int MAX_LLM_ATTEMPTS = 3;

    private static final String SYSTEM_PROMPT = """
            You are a financial-data journalist (FT / Bloomberg register) writing
            ONE paragraph identifying the recurring themes of a single US-equity
            trading WEEK on IEX. Your paragraph is the top-of-page summary above
            the per-day summaries.

            OUTPUT: one paragraph, 3-6 sentences, ~450-750 chars. Gloss acronyms
            (LULD, NBBO, MCB, VWAP, HFT) on first use. Lead with the most notable
            week-level theme. Synthesize across days — do NOT just restate one
            day's paragraph.

            INPUTS (in the user prompt):
              - WEEK METADATA: dates covered, per-scorer totals, top symbols
                across the week, per-day event counts
              - PER-DAY LIST: each day's themes paragraph, in chronological order
              - PRIOR WEEKS: the preceding finalized weeks' paragraphs (use ONLY
                to frame week-over-week trends; never present prior tickers as
                this week's; never invent a longer history than what's listed)

            WHAT THE PARAGRAPH IDENTIFIES:
              - symbols or sectors recurring across multiple sessions
              - regimes building or fading across the week
              - session-phase drift across days
              - the single most notable day or event worth surfacing by name

            QUALITATIVE-ONLY RULE: do NOT enumerate counts per symbol or scorer
            type ("TQQQ had 47 X events", "32 halts occurred"). Use qualitative
            magnitude language ("heavy", "sustained", "dominated"). The daily
            syntheses already convey counts; your paragraph's job is TREND.

            GROUNDING:
              - Every ticker must appear in the per-day paragraphs.
              - Specific numbers allowed ONLY when they appear verbatim in a
                per-day paragraph. Do NOT sum, subtract, or combine numbers
                from different days.
              - No external causes (Fed, earnings, geopolitics). No intent
                claims. No severity editorializing.

            STREAK CLAIMS: the user prompt's "ALLOWED STREAK PHRASING" section
            is the COMPLETE whitelist. Any "Nth consecutive week" / "Nth straight
            week" phrasing must exactly match an entry there. If the whitelist
            says NONE, omit streak phrasing entirely. When unsure, "continuing
            from last week" is always safe (asserts no specific count).

            CANONICAL VOCABULARY (consistency across narrations is load-bearing):
              - Baselines: "the trailing 2-week median". NEVER literal day
                counts ("14-day", "10-day") — varies by symbol.
              - Multipliers: "22.2x the trailing median" (1-decimal + "x").
              - Slippage: "X bps slippage" / "slipped X bps".
              - Depth removal: "removed X% of displayed depth".
              - Iceberg ratio: "the displayed tip represented N% of total executed".

            The per-day paragraphs already use this vocabulary; mirror it when
            referencing their metrics.

            STYLE: vary your lede. The model has good journalist instincts —
            use them. Do not reuse the same opening framing or vocabulary across
            successive weeks' paragraphs.
            """;

    @Override
    public long aggregate(final LocalDate anyDateInWeek) {
        ActivityExecutionContext actx = Activity.getExecutionContext();
        ObjectMapper json = new ObjectMapper();
        LlamaClient llama = LlamaClient.fromEnv();

        LocalDate weekStart = anyDateInWeek.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEndExclusive = weekStart.plusDays(7);   // Mon .. Sun inclusive

        // Background heartbeat — same band-aid removal as SynthesizeDay.
        try (BackgroundHeartbeat hb = BackgroundHeartbeat.start(actx, "aggregate-week-heartbeat", 30);
             Connection conn = openConnection()) {
            SchemaManager.apply(conn);

            hb.setStage("load");
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

            hb.setStage("rollup");
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

            // Week-aggregated attribution truth maps — same shape as the
            // per-day maps SynthesizeDayActivityImpl builds, but summed across
            // the week's selected_events. Used by the new AttributionVerifier
            // path in SynthesisVerifier's 5-arg verify(). NOT included in the
            // LLM-facing prompt (the prompt sees only week_aggregates JSON +
            // day paragraphs); the maps are pure verifier-side truth.
            //
            // R5 / option D (2026-05-28 evening): closes the misattribution
            // hole at the week tier. A claim like "TQQQ saw 80 events this
            // week" needs to be checked against the actual cross-day sum,
            // which is structurally different from the per-day sum.
            hb.setStage("attribution_maps");
            Map<String, Map<String, Integer>> weekBySymbolByScorer = new HashMap<>();
            Map<String, Integer> weekBySymbolTotal = new HashMap<>();
            PeriodAttributionMaps.load(conn, weekStart, weekEndExclusive,
                    weekBySymbolByScorer, weekBySymbolTotal);

            hb.setStage("prompt");
            String userPrompt = buildUserPrompt(weekStart, weekEnd, weekAggregates, days, priors);

            SynthesisVerifier verifier = new SynthesisVerifier();
            // The longest honest streak this week can claim = prior weeks + this one.
            // (Safety net behind the prompt's streak bound; catches written/digit
            // ordinals the number-grounding verifier doesn't, e.g. "fifth
            // consecutive week" off a single prior week.)
            int allowedStreak = priors.size() + 1;

            // Verifier-driven retry: re-roll on a rejected rollup (a derived /
            // cross-week-summed number, a mis-tokenized ticker, or a streak claim
            // beyond the priors) — AGGREGATE runs at temp 1.0, so a re-roll
            // usually grounds. Keep first passing, else last.
            hb.setStage("llm");
            String aggregate = null;
            SynthesisVerifier.Result verify = null;
            boolean streakOk = true;
            long llmElapsedMs = 0;
            for (int attempt = 1; attempt <= MAX_LLM_ATTEMPTS; attempt++) {
                long llmT0 = System.nanoTime();
                aggregate = llama.chat(SYSTEM_PROMPT, userPrompt, SamplingParams.AGGREGATE).trim();
                llmElapsedMs = (System.nanoTime() - llmT0) / 1_000_000L;
                verify = verifier.verify(aggregate, weekTickers, haystack.toString(),
                        weekBySymbolByScorer, weekBySymbolTotal);
                int claimedStreak = maxStreakWeeksClaimed(aggregate);
                streakOk = claimedStreak <= allowedStreak;
                if (verify.passed() && streakOk) {
                    if (attempt > 1) LOG.info("aggregate verifier passed on retry  week_start={} attempt={}", weekStart, attempt);
                    break;
                }
                LOG.warn("aggregate verifier failed  week_start={} attempt={}/{} mismatches={} streak_claimed={} allowed={}",
                        weekStart, attempt, MAX_LLM_ATTEMPTS, verify.mismatches(), claimedStreak, allowedStreak);
            }

            hb.setStage("build_data_table");
            ObjectNode dataTable;
            try {
                dataTable = com.longexposure.synth.WeeklyDataTableBuilder.build(conn, weekStart, weekEnd, json);
            } catch (Exception e) {
                LOG.warn("weekly data_table build failed (non-fatal)  week_start={} err={}", weekStart, e.getMessage());
                dataTable = json.createObjectNode();
                dataTable.put("week_start", weekStart.toString());
                dataTable.put("build_error", e.getMessage());
            }

            hb.setStage("upsert");
            upsert(conn, weekStart, weekEnd, aggregate, weekAggregates, days.size(),
                    verify, streakOk, allowedStreak, contentHash, dataTable, json);

            LOG.info("aggregated  week_start={} week_end={} days={} llm_ms={} verifier_passed={} streak_ok={} mismatches={}",
                    weekStart, weekEnd, days.size(), llmElapsedMs,
                    verify.passed() && streakOk, streakOk, verify.mismatches().size());
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
            int n = priors.size();
            sb.append("PRIOR WEEKS (").append(n).append(" week").append(n == 1 ? "" : "s")
              .append(" of trend context — the ONLY earlier weeks you know about; ")
              .append("do NOT present these as this week's activity):\n\n");
            // Chronological (oldest-first) reads more naturally as a trend run-up.
            for (int i = priors.size() - 1; i >= 0; i--) {
                PriorWeek p = priors.get(i);
                sb.append("[week of ").append(p.weekStart).append("] ")
                  .append(p.aggregateText == null ? "(no aggregate)" : p.aggregateText.trim())
                  .append("\n\n");
            }
        }
        // Whitelist-based streak constraint — replaces the older "longest honest
        // streak is N+1" arithmetic-trust language. Model picks a verbatim form
        // from this whitelist or omits streak phrasing entirely.
        sb.append(com.longexposure.synth.StreakPhrasings.allowedStreakPhrasings(priors.size(), "week"))
          .append("\n\n");

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
                        final boolean streakOk, final int allowedStreak,
                        final byte[] contentHash, final ObjectNode dataTable,
                        final ObjectMapper json) throws Exception {
        // verifier_passed reflects BOTH the number/ticker verifier and the
        // streak-bound check, so a fabricated streak is stored as a failure
        // (filterable, not served by the API) even though the number verifier
        // didn't catch the written ordinal.
        boolean passed = verify.passed() && streakOk;
        ObjectNode verifierNotes = json.createObjectNode();
        verifierNotes.put("tickers_checked", verify.tickersChecked());
        verifierNotes.put("numbers_checked", verify.numbersChecked());
        verifierNotes.put("streak_ok", streakOk);
        ArrayNode mismatches = json.valueToTree(verify.mismatches());
        if (!streakOk) {
            mismatches.add("streak claim exceeds the " + allowedStreak
                    + "-week ceiling (prior weeks + 1) — unsupported by the prior-week window");
        }
        verifierNotes.set("mismatches", mismatches);

        String sql = """
                INSERT INTO weekly_aggregate (
                    week_start, week_end, aggregate_text, days_considered,
                    week_aggregates, model_id, prompt_version,
                    verifier_passed, verifier_notes, content_hash, data_table
                )
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?::jsonb)
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
                    data_table      = EXCLUDED.data_table,
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
            st.setBoolean(8, passed);
            st.setString(9, verifierNotes.toString());
            st.setBytes(10, contentHash);
            st.setString(11, dataTable.toString());
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

    private static final java.util.regex.Pattern STREAK_RE = java.util.regex.Pattern.compile(
            "\\b(\\d{1,2}(?:st|nd|rd|th)?|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth)"
            + "\\s+(?:consecutive|straight)\\s+weeks?\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.Map<String, Integer> ORD_WORDS = java.util.Map.ofEntries(
            java.util.Map.entry("first", 1), java.util.Map.entry("second", 2), java.util.Map.entry("third", 3),
            java.util.Map.entry("fourth", 4), java.util.Map.entry("fifth", 5), java.util.Map.entry("sixth", 6),
            java.util.Map.entry("seventh", 7), java.util.Map.entry("eighth", 8), java.util.Map.entry("ninth", 9),
            java.util.Map.entry("tenth", 10), java.util.Map.entry("eleventh", 11), java.util.Map.entry("twelfth", 12));

    /**
     * Largest "Nth consecutive/straight week(s)" streak length claimed in the
     * prose (0 if none). Catches both word ordinals ("fifth consecutive week")
     * and digit forms ("5th straight week"). The safety net behind the prompt's
     * streak bound — the number-grounding verifier misses these because written
     * ordinals aren't digit-numbers.
     */
    static int maxStreakWeeksClaimed(final String text) {
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
