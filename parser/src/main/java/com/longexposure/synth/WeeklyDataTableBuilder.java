package com.longexposure.synth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.longexposure.scoring.BreakdownFmt;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Weekly equivalent of {@link DailyDataTableBuilder}. Renders ABOVE the
 * weekly AGGREGATE prose paragraph, including a deterministic 3-4 bullet
 * "executive summary" for 10-second skim.
 *
 * <p>Pure SQL, no LLM. Computed inside {@code AggregateWeekActivityImpl}
 * alongside the weekly prose. Stored in {@code weekly_aggregate.data_table}.
 *
 * <p>JSON shape:
 * <pre>{@code
 * {
 *   "week_start": "2026-05-11",
 *   "week_end":   "2026-05-15",
 *   "executive_summary": [        // 3-4 bullets for the 10-second skim
 *     "TQQQ liquidity withdrawals every session (28 events)",
 *     "Pre-market halts dominated opens (38 across 5 sessions)",
 *     "Sweep activity concentrated in semis: NVDA, AMD, SMH",
 *     "Largest block: STX $117.91M on 2026-05-11"
 *   ],
 *   "headline_events": [          // top 10 events of the week by score
 *     {"date": "2026-05-13", "symbol": "STX", "scorer": "large_trade", "metric": "$117.91M", "time": "11:08 ET"},
 *     ...
 *   ],
 *   "per_day": [                  // per-day event counts + dominant scorer
 *     {"date": "2026-05-11", "total": 164, "dominant_scorer": "liquidity_withdrawal", "notable_symbol": "TQQQ"},
 *     ...
 *   ],
 *   "top_symbols": [              // top 10 across the week with day-presence + scorer mix
 *     {"symbol": "TQQQ", "total_events": 28, "days_present": 5, "scorer_mix": {"liquidity_withdrawal": 15, ...}},
 *     ...
 *   ],
 *   "scorer_mix": { ... },        // total per scorer for the week
 *   "notable_extremes": { ... }   // largest block, longest halt, etc.
 * }
 * }</pre>
 */
public final class WeeklyDataTableBuilder {

    private static final int HEADLINE_LIMIT = 10;
    private static final int TOP_SYMBOLS_LIMIT = 10;
    private static final int EXEC_SUMMARY_MAX_BULLETS = 5;

    private WeeklyDataTableBuilder() {}

    /** Magnitude threshold for the "+N% vs prior week" bullet. */
    private static final int VS_PRIOR_MIN_PCT_CHANGE = 20;

    public static ObjectNode build(final Connection conn,
                                    final LocalDate weekStart,
                                    final LocalDate weekEnd,
                                    final ObjectMapper json) throws SQLException {
        ObjectNode root = json.createObjectNode();
        root.put("week_start", weekStart.toString());
        root.put("week_end",   weekEnd.toString());

        ArrayNode topSymbols = buildTopSymbols(conn, weekStart, weekEnd, json);
        ArrayNode headline   = buildHeadlineEvents(conn, weekStart, weekEnd, json);
        ArrayNode perDay     = buildPerDay(conn, weekStart, weekEnd, json);
        ObjectNode scorerMix = buildScorerMix(conn, weekStart, weekEnd, json);
        ObjectNode extremes  = buildNotableExtremes(conn, weekStart, weekEnd, json);
        ObjectNode vsPriorWeek = buildVsPriorWeek(conn, weekStart, weekEnd, topSymbols, scorerMix, json);

        // Executive summary derived from the structured data above —
        // deterministic, no LLM.
        root.set("executive_summary",
                buildExecutiveSummary(topSymbols, perDay, scorerMix, extremes, vsPriorWeek, json));
        root.set("headline_events",   headline);
        root.set("per_day",           perDay);
        root.set("top_symbols",       topSymbols);
        root.set("scorer_mix",        scorerMix);
        root.set("notable_extremes",  extremes);
        if (vsPriorWeek != null) root.set("vs_prior_week", vsPriorWeek);
        return root;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Executive summary — 3-4 deterministic bullets for 10-second skim
    // ═══════════════════════════════════════════════════════════════════════

    private static ArrayNode buildExecutiveSummary(final ArrayNode topSymbols,
                                                    final ArrayNode perDay,
                                                    final ObjectNode scorerMix,
                                                    final ObjectNode extremes,
                                                    final ObjectNode vsPriorWeek,
                                                    final ObjectMapper json) {
        ArrayNode bullets = json.createArrayNode();
        List<String> candidates = new ArrayList<>();

        // Bullet 1 — most-active symbol with day-presence framing.
        if (topSymbols.size() > 0) {
            JsonNode top = topSymbols.get(0);
            String sym = top.path("symbol").asText("");
            int events = top.path("total_events").asInt(0);
            int days = top.path("days_present").asInt(0);
            if (!sym.isEmpty()) {
                String dayFrame = (days >= 5) ? "every session"
                                : (days >= 4) ? "in " + days + " of 5 sessions"
                                : "across " + days + " sessions";
                candidates.add(sym + " led activity with " + events + " events " + dayFrame);
            }
        }

        // Bullet 2 — dominant scorer type for the week.
        String topScorer = null;
        long topScorerCount = 0;
        java.util.Iterator<String> sit = scorerMix.fieldNames();
        while (sit.hasNext()) {
            String s = sit.next();
            long c = scorerMix.path(s).asLong(0);
            if (c > topScorerCount) { topScorerCount = c; topScorer = s; }
        }
        if (topScorer != null && topScorerCount > 0) {
            candidates.add(humanScorer(topScorer) + " dominated the week ("
                    + topScorerCount + " events)");
        }

        // Bullet 3 — largest block (or longest halt if no block stood out).
        JsonNode largestBlock = extremes.path("largest_notional_block");
        if (largestBlock.isObject()) {
            String sym   = largestBlock.path("symbol").asText("");
            String value = largestBlock.path("value").asText("");
            String date  = largestBlock.path("date").asText("");
            if (!sym.isEmpty() && !value.isEmpty()) {
                candidates.add("Largest block: " + sym + " $" + value + "M"
                        + (date.isEmpty() ? "" : " on " + date));
            }
        }

        // Bullet 4 — longest halt OR biggest depth removal.
        JsonNode longestHalt = extremes.path("longest_halt");
        if (longestHalt.isObject()) {
            String sym   = longestHalt.path("symbol").asText("");
            String value = longestHalt.path("value").asText("");
            if (!sym.isEmpty() && !value.isEmpty()) {
                candidates.add("Longest halt: " + sym + " " + value);
            }
        }

        // Bullet 5 candidates — vs_prior_week + fallback daily range.
        // Priority: cross-period change first (the trend story), then fallback.
        if (vsPriorWeek != null) {
            for (String b : vsPriorWeekBullets(vsPriorWeek)) candidates.add(b);
        }
        if (perDay.size() >= 2) {
            int max = 0, min = Integer.MAX_VALUE;
            String maxDate = "", minDate = "";
            for (JsonNode d : perDay) {
                int t = d.path("total").asInt(0);
                if (t > max) { max = t; maxDate = d.path("date").asText(""); }
                if (t < min) { min = t; minDate = d.path("date").asText(""); }
            }
            if (max > 0 && min > 0 && max != min) {
                candidates.add("Daily range: " + min + " events (" + minDate
                        + ") to " + max + " (" + maxDate + ")");
            }
        }

        for (int i = 0; i < Math.min(candidates.size(), EXEC_SUMMARY_MAX_BULLETS); i++) {
            bullets.add(candidates.get(i));
        }
        return bullets;
    }

    /**
     * Bullets sourced from vs_prior_week. Emits a volume-change bullet
     * when pct change crosses the threshold, and a scorer-persistence
     * bullet when the same scorer dominated both this week and prior.
     */
    private static List<String> vsPriorWeekBullets(final ObjectNode vsPriorWeek) {
        List<String> out = new ArrayList<>();
        int pct = vsPriorWeek.path("total_events_pct_change").asInt(0);
        if (Math.abs(pct) >= VS_PRIOR_MIN_PCT_CHANGE) {
            out.add("Event volume " + (pct > 0 ? "+" : "") + pct + "% vs prior week");
        }
        boolean scorerPersisted = vsPriorWeek.path("dominant_scorer_persisted").asBoolean(false);
        int scorerStreak = vsPriorWeek.path("dominant_scorer_streak").asInt(1);
        String topScorer = vsPriorWeek.path("dominant_scorer").asText("");
        if (scorerPersisted && scorerStreak >= 2 && !topScorer.isEmpty()) {
            out.add(humanScorer(topScorer) + " dominated for the "
                    + ordinal(scorerStreak) + " consecutive week");
        }
        boolean symbolPersisted = vsPriorWeek.path("top_symbol_persisted").asBoolean(false);
        int symbolStreak = vsPriorWeek.path("top_symbol_streak").asInt(1);
        String topSymbol = vsPriorWeek.path("top_symbol").asText("");
        if (symbolPersisted && symbolStreak >= 2 && !topSymbol.isEmpty()) {
            out.add(topSymbol + " led activity for the " + ordinal(symbolStreak)
                    + " consecutive week");
        }
        return out;
    }

    private static String ordinal(final int n) {
        if (n % 100 >= 11 && n % 100 <= 13) return n + "th";
        return switch (n % 10) {
            case 1 -> n + "st";
            case 2 -> n + "nd";
            case 3 -> n + "rd";
            default -> n + "th";
        };
    }

    // ═══════════════════════════════════════════════════════════════════════
    // vs_prior_week: cross-period comparison vs the prior ISO week
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Compares this week's per-scorer + top-symbol picture against the
     * prior ISO week (weekStart - 7d). Returns null if the prior week has
     * no selected_events (first row in dataset).
     *
     * <p>Persistence semantics are the narrow form: top-1 this week
     * matches top-1 of the prior week. Streak walks backwards through
     * weeks to find how long it's been true.
     */
    private static ObjectNode buildVsPriorWeek(final Connection conn,
                                                 final LocalDate weekStart,
                                                 final LocalDate weekEnd,
                                                 final ArrayNode todayTopSymbols,
                                                 final ObjectNode todayScorerMix,
                                                 final ObjectMapper json) throws SQLException {
        LocalDate priorWeekStart = weekStart.minusDays(7);
        LocalDate priorWeekEnd   = weekEnd.minusDays(7);

        long priorTotal = countRange(conn, priorWeekStart, priorWeekEnd);
        if (priorTotal == 0) return null;  // first week in dataset

        ObjectNode out = json.createObjectNode();
        out.put("prior_week_start", priorWeekStart.toString());
        out.put("prior_week_end",   priorWeekEnd.toString());

        long todayTotal = countRange(conn, weekStart, weekEnd);
        out.put("today_total_events", todayTotal);
        out.put("prior_total_events", priorTotal);
        out.put("total_events_pct_change", pctChange(priorTotal, todayTotal));

        String todayTopScorer = topScorerInRange(todayScorerMix);
        String priorTopScorer = topScorerForRange(conn, priorWeekStart, priorWeekEnd);
        out.put("dominant_scorer", todayTopScorer == null ? "" : todayTopScorer);
        out.put("prior_dominant_scorer", priorTopScorer == null ? "" : priorTopScorer);
        boolean scorerPersisted = todayTopScorer != null && todayTopScorer.equals(priorTopScorer);
        out.put("dominant_scorer_persisted", scorerPersisted);
        int scorerStreak = scorerPersisted ? scorerStreakWeeks(conn, weekStart, weekEnd, todayTopScorer) : 1;
        out.put("dominant_scorer_streak", scorerStreak);

        String todayTopSymbol = topSymbolInRange(todayTopSymbols);
        String priorTopSymbol = topSymbolForRange(conn, priorWeekStart, priorWeekEnd);
        out.put("top_symbol", todayTopSymbol == null ? "" : todayTopSymbol);
        out.put("prior_top_symbol", priorTopSymbol == null ? "" : priorTopSymbol);
        boolean symbolPersisted = todayTopSymbol != null && todayTopSymbol.equals(priorTopSymbol);
        out.put("top_symbol_persisted", symbolPersisted);
        int symbolStreak = symbolPersisted ? symbolStreakWeeks(conn, weekStart, weekEnd, todayTopSymbol) : 1;
        out.put("top_symbol_streak", symbolStreak);

        // Per-scorer week-over-week changes.
        ObjectNode byScorerChanges = json.createObjectNode();
        ObjectNode priorScorerMix = scorerMixForRange(conn, priorWeekStart, priorWeekEnd, json);
        java.util.Set<String> allScorers = new java.util.HashSet<>();
        if (todayScorerMix != null) todayScorerMix.fieldNames().forEachRemaining(allScorers::add);
        priorScorerMix.fieldNames().forEachRemaining(allScorers::add);
        for (String s : allScorers) {
            long today = todayScorerMix != null ? todayScorerMix.path(s).asLong(0) : 0;
            long prior = priorScorerMix.path(s).asLong(0);
            byScorerChanges.put(s, (int) (today - prior));
        }
        out.set("by_scorer_changes", byScorerChanges);

        return out;
    }

    private static long countRange(final Connection conn,
                                    final LocalDate from, final LocalDate to) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT COUNT(*) FROM selected_events WHERE trading_date BETWEEN ? AND ?")) {
            st.setObject(1, from);
            st.setObject(2, to);
            try (ResultSet rs = st.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
        }
    }

    private static String topScorerForRange(final Connection conn,
                                              final LocalDate from, final LocalDate to) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT scorer_id FROM selected_events WHERE trading_date BETWEEN ? AND ? "
                  + "GROUP BY scorer_id ORDER BY COUNT(*) DESC LIMIT 1")) {
            st.setObject(1, from);
            st.setObject(2, to);
            try (ResultSet rs = st.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    private static String topSymbolForRange(final Connection conn,
                                              final LocalDate from, final LocalDate to) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT symbol FROM selected_events WHERE trading_date BETWEEN ? AND ? "
                  + "GROUP BY symbol ORDER BY COUNT(*) DESC LIMIT 1")) {
            st.setObject(1, from);
            st.setObject(2, to);
            try (ResultSet rs = st.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    private static ObjectNode scorerMixForRange(final Connection conn,
                                                  final LocalDate from, final LocalDate to,
                                                  final ObjectMapper json) throws SQLException {
        ObjectNode out = json.createObjectNode();
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT scorer_id, COUNT(*) FROM selected_events "
                  + "WHERE trading_date BETWEEN ? AND ? GROUP BY scorer_id")) {
            st.setObject(1, from);
            st.setObject(2, to);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getLong(2));
            }
        }
        return out;
    }

    /** Walk backwards by ISO week, counting consecutive weeks where the
     * dominant scorer equals {@code scorerId}. Capped at 26 weeks. */
    private static int scorerStreakWeeks(final Connection conn,
                                          final LocalDate weekStart, final LocalDate weekEnd,
                                          final String scorerId) throws SQLException {
        int streak = 0;
        LocalDate s = weekStart, e = weekEnd;
        for (int i = 0; i < 26; i++) {
            String top = topScorerForRange(conn, s, e);
            if (top == null || !top.equals(scorerId)) break;
            streak++;
            s = s.minusDays(7);
            e = e.minusDays(7);
        }
        return streak;
    }

    /** Walk backwards by ISO week, counting consecutive weeks where the
     * top symbol equals {@code symbol}. Capped at 26 weeks. */
    private static int symbolStreakWeeks(final Connection conn,
                                          final LocalDate weekStart, final LocalDate weekEnd,
                                          final String symbol) throws SQLException {
        int streak = 0;
        LocalDate s = weekStart, e = weekEnd;
        for (int i = 0; i < 26; i++) {
            String top = topSymbolForRange(conn, s, e);
            if (top == null || !top.equals(symbol)) break;
            streak++;
            s = s.minusDays(7);
            e = e.minusDays(7);
        }
        return streak;
    }

    private static int pctChange(final long prior, final long today) {
        if (prior == 0) return today == 0 ? 0 : 100;
        return (int) Math.round(100.0 * (today - prior) / prior);
    }

    private static String topScorerInRange(final ObjectNode scorerMix) {
        if (scorerMix == null || scorerMix.isMissingNode()) return null;
        String top = null;
        long max = 0;
        java.util.Iterator<String> it = scorerMix.fieldNames();
        while (it.hasNext()) {
            String s = it.next();
            long c = scorerMix.path(s).asLong(0);
            if (c > max) { max = c; top = s; }
        }
        return top;
    }

    private static String topSymbolInRange(final ArrayNode topSymbols) {
        if (topSymbols == null || topSymbols.size() == 0) return null;
        return topSymbols.get(0).path("symbol").asText(null);
    }

    private static String humanScorer(final String scorerId) {
        return switch (scorerId) {
            case "halt"                 -> "Trading halts";
            case "large_trade"          -> "Large block trades";
            case "sweep"                -> "Multi-level sweeps";
            case "iceberg"              -> "Iceberg execution";
            case "layering"             -> "Layering";
            case "post_cancel_cluster"  -> "Post-cancel clusters";
            case "liquidity_withdrawal" -> "Liquidity withdrawals";
            case "volume_deviation"     -> "Volume surges";
            case "time_in_book_drift"   -> "Order-lifetime regime shifts";
            default                      -> scorerId;
        };
    }

    // ═══════════════════════════════════════════════════════════════════════
    // top_symbols across the week
    // ═══════════════════════════════════════════════════════════════════════

    private static ArrayNode buildTopSymbols(final Connection conn,
                                              final LocalDate weekStart,
                                              final LocalDate weekEnd,
                                              final ObjectMapper json) throws SQLException {
        ArrayNode out = json.createArrayNode();
        String sql = """
                SELECT symbol,
                       COUNT(*) AS total_events,
                       COUNT(DISTINCT trading_date) AS days_present
                  FROM selected_events
                 WHERE trading_date BETWEEN ? AND ?
                 GROUP BY symbol
                 ORDER BY total_events DESC
                 LIMIT ?
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, weekStart);
            st.setObject(2, weekEnd);
            st.setInt(3, TOP_SYMBOLS_LIMIT);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    ObjectNode row = json.createObjectNode();
                    row.put("symbol",       rs.getString(1));
                    row.put("total_events", rs.getLong(2));
                    row.put("days_present", rs.getInt(3));
                    row.set("scorer_mix",   scorerMixForSymbol(conn, rs.getString(1), weekStart, weekEnd, json));
                    out.add(row);
                }
            }
        }
        return out;
    }

    private static ObjectNode scorerMixForSymbol(final Connection conn,
                                                   final String symbol,
                                                   final LocalDate weekStart,
                                                   final LocalDate weekEnd,
                                                   final ObjectMapper json) throws SQLException {
        ObjectNode out = json.createObjectNode();
        String sql = """
                SELECT scorer_id, COUNT(*) FROM selected_events
                 WHERE symbol = ? AND trading_date BETWEEN ? AND ?
                 GROUP BY scorer_id ORDER BY COUNT(*) DESC
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, symbol);
            st.setObject(2, weekStart);
            st.setObject(3, weekEnd);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getLong(2));
            }
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // headline_events — top events of the week by score
    // ═══════════════════════════════════════════════════════════════════════

    private static ArrayNode buildHeadlineEvents(final Connection conn,
                                                  final LocalDate weekStart,
                                                  final LocalDate weekEnd,
                                                  final ObjectMapper json) throws SQLException {
        ArrayNode out = json.createArrayNode();
        String sql = """
                SELECT trading_date, scorer_id, symbol, breakdown, score
                  FROM selected_events
                 WHERE trading_date BETWEEN ? AND ?
                 ORDER BY score DESC
                 LIMIT ?
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, weekStart);
            st.setObject(2, weekEnd);
            st.setInt(3, HEADLINE_LIMIT);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    ObjectNode row = json.createObjectNode();
                    String scorerId = rs.getString("scorer_id");
                    try {
                        ObjectNode bd = (ObjectNode) json.readTree(rs.getString("breakdown"));
                        row.put("date",    rs.getDate("trading_date").toString());
                        row.put("symbol",  rs.getString("symbol"));
                        row.put("scorer",  scorerId);
                        row.put("metric",  dailyHeadlineMetric(scorerId, bd));
                        row.put("time",    dailyHeadlineTime(scorerId, bd));
                    } catch (Exception e) {
                        // skip malformed row
                        continue;
                    }
                    out.add(row);
                }
            }
        }
        return out;
    }

    /** Mirror of DailyDataTableBuilder.headlineMetricFor — same shape, shared. */
    private static String dailyHeadlineMetric(final String scorerId, final ObjectNode bd) {
        return switch (scorerId) {
            case "halt"                 -> textOr(bd, "halt_duration", "") + " halt";
            case "large_trade"          -> textOr(bd, "notional_dollars", "$?") + " block";
            case "sweep"                -> textOr(bd, "notional_dollars", "$?") + " sweep";
            case "iceberg"              -> intOr(bd, "fills", 0) + " fills";
            case "layering"             -> intOr(bd, "orders", 0) + " orders, "
                                            + intOr(bd, "distinct_levels", 0) + " levels";
            case "post_cancel_cluster"  -> intOr(bd, "orders", 0) + " orders";
            case "liquidity_withdrawal" -> textOr(bd, "deletes", "?") + " deletes";
            case "volume_deviation"     -> textOr(bd, "deviation_x", "?") + "x median";
            case "time_in_book_drift"   -> textOr(bd, "drift_x", "?") + "x lifetime";
            default                      -> scorerId;
        };
    }

    private static String dailyHeadlineTime(final String scorerId, final ObjectNode bd) {
        return switch (scorerId) {
            case "halt"        -> textOr(bd, "halt_start_et", "") + " ET";
            case "large_trade" -> textOr(bd, "ts_et", "") + " ET";
            case "sweep", "iceberg", "layering",
                 "post_cancel_cluster",
                 "liquidity_withdrawal" -> textOr(bd, "start_et", "") + " ET";
            default            -> "";
        };
    }

    // ═══════════════════════════════════════════════════════════════════════
    // per_day breakdown
    // ═══════════════════════════════════════════════════════════════════════

    private static ArrayNode buildPerDay(final Connection conn,
                                          final LocalDate weekStart,
                                          final LocalDate weekEnd,
                                          final ObjectMapper json) throws SQLException {
        ArrayNode out = json.createArrayNode();
        String sql = """
                SELECT trading_date,
                       COUNT(*) AS total,
                       (SELECT scorer_id FROM selected_events s2
                         WHERE s2.trading_date = s1.trading_date
                         GROUP BY scorer_id ORDER BY COUNT(*) DESC LIMIT 1) AS dominant_scorer,
                       (SELECT symbol FROM selected_events s3
                         WHERE s3.trading_date = s1.trading_date
                         GROUP BY symbol ORDER BY COUNT(*) DESC LIMIT 1) AS notable_symbol
                  FROM selected_events s1
                 WHERE trading_date BETWEEN ? AND ?
                 GROUP BY trading_date
                 ORDER BY trading_date
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, weekStart);
            st.setObject(2, weekEnd);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    ObjectNode row = json.createObjectNode();
                    row.put("date",            rs.getDate("trading_date").toString());
                    row.put("total",           rs.getLong("total"));
                    row.put("dominant_scorer", rs.getString("dominant_scorer"));
                    row.put("notable_symbol",  rs.getString("notable_symbol"));
                    out.add(row);
                }
            }
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // scorer_mix totals
    // ═══════════════════════════════════════════════════════════════════════

    private static ObjectNode buildScorerMix(final Connection conn,
                                               final LocalDate weekStart,
                                               final LocalDate weekEnd,
                                               final ObjectMapper json) throws SQLException {
        ObjectNode out = json.createObjectNode();
        String sql = "SELECT scorer_id, COUNT(*) FROM selected_events "
                + "WHERE trading_date BETWEEN ? AND ? GROUP BY scorer_id ORDER BY scorer_id";
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, weekStart);
            st.setObject(2, weekEnd);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), rs.getLong(2));
            }
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // notable_extremes for the week
    // ═══════════════════════════════════════════════════════════════════════

    private static ObjectNode buildNotableExtremes(final Connection conn,
                                                     final LocalDate weekStart,
                                                     final LocalDate weekEnd,
                                                     final ObjectMapper json) throws SQLException {
        ObjectNode out = json.createObjectNode();
        addWeekExtreme(out, conn, weekStart, weekEnd, json,
                "largest_notional_block", "large_trade",
                "(breakdown->>'notional_million_dollars')::float",
                "notional_million_dollars");
        addWeekExtreme(out, conn, weekStart, weekEnd, json,
                "largest_notional_sweep", "sweep",
                "(breakdown->>'notional_million_dollars')::float",
                "notional_million_dollars");
        addWeekExtreme(out, conn, weekStart, weekEnd, json,
                "longest_halt", "halt",
                "(breakdown->>'halt_duration_seconds')::float",
                "halt_duration");
        addWeekExtreme(out, conn, weekStart, weekEnd, json,
                "largest_pct_depth_removed", "liquidity_withdrawal",
                "(breakdown->>'pct_of_book_removed')::float",
                "pct_of_book_removed");
        addWeekExtreme(out, conn, weekStart, weekEnd, json,
                "highest_volume_deviation", "volume_deviation",
                "(breakdown->>'deviation_x')::float",
                "deviation_x");
        addWeekExtreme(out, conn, weekStart, weekEnd, json,
                "biggest_lifetime_drift", "time_in_book_drift",
                "(breakdown->>'drift_x')::float",
                "drift_x");
        return out;
    }

    private static void addWeekExtreme(final ObjectNode parent,
                                        final Connection conn,
                                        final LocalDate weekStart,
                                        final LocalDate weekEnd,
                                        final ObjectMapper json,
                                        final String key,
                                        final String scorerId,
                                        final String orderExpr,
                                        final String valueField) throws SQLException {
        String sql = "SELECT trading_date, symbol, breakdown->>'" + valueField + "' AS v "
                + "  FROM selected_events "
                + " WHERE trading_date BETWEEN ? AND ? AND scorer_id=? "
                + "       AND " + orderExpr + " IS NOT NULL "
                + " ORDER BY " + orderExpr + " DESC NULLS LAST "
                + " LIMIT 1";
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, weekStart);
            st.setObject(2, weekEnd);
            st.setString(3, scorerId);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    ObjectNode row = json.createObjectNode();
                    row.put("date",   rs.getDate("trading_date").toString());
                    row.put("symbol", rs.getString("symbol"));
                    row.put("value",  rs.getString("v"));
                    row.put("scorer", scorerId);
                    parent.set(key, row);
                }
            }
        }
    }

    private static String textOr(final ObjectNode bd, final String key, final String def) {
        if (bd.has(key) && !bd.get(key).isNull()) return bd.get(key).asText();
        return def;
    }

    private static int intOr(final ObjectNode bd, final String key, final int def) {
        if (bd.has(key) && bd.get(key).canConvertToInt()) return bd.get(key).asInt();
        return def;
    }
}
