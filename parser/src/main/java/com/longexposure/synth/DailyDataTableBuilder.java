package com.longexposure.synth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.longexposure.scoring.BreakdownFmt;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

/**
 * Builds the structured journalist-format data table that renders ABOVE the
 * SYNTHESIZE prose on the per-day view. Pure SQL, no LLM, deterministic.
 *
 * <p>Computed inside {@code SynthesizeDayActivityImpl} alongside the synth
 * paragraph — same Postgres connection, same activity invocation. Stored in
 * {@code daily_synthesis.data_table} as JSONB.
 *
 * <p>JSON shape (consumed by the {@code long-exposure-browser} frontend):
 *
 * <pre>{@code
 * {
 *   "trading_date": "2026-05-11",
 *   "headline": [               // top 5 events of the day by score, all scorers
 *     {"symbol": "STX", "scorer": "large_trade", "metric": "$117.91M notional", "time": "11:08 ET"},
 *     ...
 *   ],
 *   "per_scorer_top": {         // top N per scorer with scorer-specific columns
 *     "halt":                 [ ... ],
 *     "large_trade":          [ ... ],
 *     "sweep":                [ ... ],
 *     "iceberg":              [ ... ],
 *     "layering":             [ ... ],
 *     "post_cancel_cluster":  [ ... ],
 *     "liquidity_withdrawal": [ ... ],
 *     "volume_deviation":     [ ... ],
 *     "time_in_book_drift":   [ ... ]
 *   },
 *   "day_summary": {
 *     "total_scored_events": ...,
 *     "total_narrated_events": ...,
 *     "by_scorer": { ... },
 *     "by_session_phase": { ... },
 *     "top_symbols": [ ... ],
 *     "scorer_mix_entropy": ...,
 *     "symbol_concentration_hhi": ...
 *   },
 *   "notable_extremes": { ... }
 * }
 * }</pre>
 */
public final class DailyDataTableBuilder {

    /** How many events to include in the cross-scorer headline list. */
    private static final int HEADLINE_LIMIT = 5;

    /** How many events to include per scorer in the per-scorer top sections. */
    private static final int PER_SCORER_LIMIT = 5;

    /** How many top symbols (by narrated event count) to include in day_summary. */
    private static final int TOP_SYMBOLS_LIMIT = 10;

    private DailyDataTableBuilder() {}

    public static ObjectNode build(final Connection conn,
                                    final LocalDate tradingDate,
                                    final ObjectMapper json) throws SQLException {
        ObjectNode root = json.createObjectNode();
        root.put("trading_date", tradingDate.toString());
        root.set("headline", buildHeadline(conn, tradingDate, json));
        root.set("per_scorer_top", buildPerScorerTop(conn, tradingDate, json));
        root.set("day_summary", buildDaySummary(conn, tradingDate, json));
        root.set("notable_extremes", buildNotableExtremes(conn, tradingDate, json));
        return root;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // headline: top events of the day by score, across all scorers
    // ═══════════════════════════════════════════════════════════════════════

    private static ArrayNode buildHeadline(final Connection conn,
                                            final LocalDate tradingDate,
                                            final ObjectMapper json) throws SQLException {
        ArrayNode out = json.createArrayNode();
        String sql = """
                SELECT scorer_id, symbol, breakdown, score, ts
                  FROM selected_events
                 WHERE trading_date = ?
                 ORDER BY score DESC
                 LIMIT ?
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, tradingDate);
            st.setInt(2, HEADLINE_LIMIT);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    ObjectNode row = json.createObjectNode();
                    String scorerId = rs.getString("scorer_id");
                    String symbol   = rs.getString("symbol");
                    ObjectNode bd   = (ObjectNode) json.readTree(rs.getString("breakdown"));

                    row.put("symbol", symbol);
                    row.put("scorer", scorerId);
                    row.put("metric", headlineMetricFor(scorerId, bd));
                    row.put("time",   headlineTimeFor(scorerId, bd));
                    out.add(row);
                }
            } catch (Exception e) {
                throw new SQLException("headline build failed", e);
            }
        }
        return out;
    }

    /**
     * The "one-line headline" for an event — the metric a journalist would
     * lead with for this scorer type. Drawn from pre-formatted breakdown
     * fields. Pure data layer; no inference.
     */
    private static String headlineMetricFor(final String scorerId, final ObjectNode bd) {
        return switch (scorerId) {
            case "halt"                 -> textOr(bd, "halt_duration", "")
                                            + " halt ("
                                            + textOr(bd, "halt_reason_label", "trading halt")
                                            + ")";
            case "large_trade"          -> textOr(bd, "notional_dollars", "$?") + " block";
            case "sweep"                -> textOr(bd, "notional_dollars", "$?")
                                            + " sweep, "
                                            + intOr(bd, "distinct_levels", 0) + " levels";
            case "iceberg"              -> intOr(bd, "fills", 0) + " fills, "
                                            + textOr(bd, "total_shares", "?") + " shares";
            case "layering"             -> intOr(bd, "orders", 0) + " orders, "
                                            + intOr(bd, "distinct_levels", 0) + " levels";
            case "post_cancel_cluster"  -> intOr(bd, "orders", 0) + " orders, "
                                            + textOr(bd, "median_lifetime_ms", "?") + "ms median";
            case "liquidity_withdrawal" -> textOr(bd, "deletes", "?") + " deletes, "
                                            + textOr(bd, "pct_of_book_removed", "?") + "% of book";
            case "volume_deviation"     -> textOr(bd, "deviation_x", "?") + "x trailing median";
            case "time_in_book_drift"   -> textOr(bd, "drift_x", "?") + "x "
                                            + textOr(bd, "drift_direction", "") + " lifetime";
            default                      -> scorerId;
        };
    }

    /** The "when" column for the headline row. */
    private static String headlineTimeFor(final String scorerId, final ObjectNode bd) {
        return switch (scorerId) {
            case "halt"                          -> appendEt(textOr(bd, "halt_start_et", ""));
            case "large_trade"                   -> appendEt(textOr(bd, "ts_et", ""));
            case "sweep", "iceberg", "layering",
                 "post_cancel_cluster",
                 "liquidity_withdrawal"           -> appendEt(textOr(bd, "start_et", ""));
            case "volume_deviation",
                 "time_in_book_drift"             -> "day-level";
            default                               -> "";
        };
    }

    // ═══════════════════════════════════════════════════════════════════════
    // per_scorer_top: top N per scorer with scorer-specific columns
    // ═══════════════════════════════════════════════════════════════════════

    private static ObjectNode buildPerScorerTop(final Connection conn,
                                                 final LocalDate tradingDate,
                                                 final ObjectMapper json) throws SQLException {
        ObjectNode out = json.createObjectNode();
        for (String scorer : new String[]{
                "halt", "large_trade", "sweep", "iceberg", "layering",
                "post_cancel_cluster", "liquidity_withdrawal",
                "volume_deviation", "time_in_book_drift"}) {
            out.set(scorer, buildPerScorerRows(conn, tradingDate, scorer, json));
        }
        return out;
    }

    private static ArrayNode buildPerScorerRows(final Connection conn,
                                                 final LocalDate tradingDate,
                                                 final String scorerId,
                                                 final ObjectMapper json) throws SQLException {
        ArrayNode out = json.createArrayNode();
        String sql = """
                SELECT symbol, breakdown, score, ts
                  FROM selected_events
                 WHERE trading_date = ? AND scorer_id = ?
                 ORDER BY score DESC
                 LIMIT ?
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, tradingDate);
            st.setString(2, scorerId);
            st.setInt(3, PER_SCORER_LIMIT);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    String symbol   = rs.getString("symbol");
                    ObjectNode bd   = (ObjectNode) json.readTree(rs.getString("breakdown"));
                    out.add(perScorerRowFor(scorerId, symbol, bd, json));
                }
            } catch (Exception e) {
                throw new SQLException("per_scorer build failed for " + scorerId, e);
            }
        }
        return out;
    }

    /**
     * Scorer-specific column projection. Picks the 4-6 fields a journalist
     * reading a structured row would expect. All sourced from pre-formatted
     * breakdown fields — no inference, no LLM.
     */
    private static ObjectNode perScorerRowFor(final String scorerId,
                                                final String symbol,
                                                final ObjectNode bd,
                                                final ObjectMapper json) {
        ObjectNode row = json.createObjectNode();
        row.put("symbol", symbol);
        String companyName = textOr(bd, "company_name", null);
        if (companyName != null) row.put("company_name", companyName);

        switch (scorerId) {
            case "halt" -> {
                row.put("duration",         textOr(bd, "halt_duration", ""));
                row.put("reason",           textOr(bd, "halt_reason_label", "trading halt"));
                row.put("start_et",         appendEt(textOr(bd, "halt_start_et", "")));
                row.put("end_et",           appendEt(textOr(bd, "halt_end_et", "")));
                row.put("pct_of_session",   textOr(bd, "halt_duration_pct_of_regular_session", "") + "%");
                row.put("duration_bucket",  textOr(bd, "halt_duration_bucket_label", ""));
            }
            case "large_trade" -> {
                row.put("notional",         textOr(bd, "notional_dollars", "$?"));
                row.put("shares",           textOr(bd, "size_shares", "?"));
                row.put("pct_of_baseline",  textOr(bd, "pct_of_baseline_volume", "?") + "%");
                row.put("price",            textOr(bd, "price_dollars", "?"));
                row.put("ts_et",            appendEt(textOr(bd, "ts_et", "")));
                row.put("phase",            textOr(bd, "event_phase_label", ""));
                row.put("pre_event_ofi",    textOr(bd, "pre_event_ofi_class", ""));
            }
            case "sweep" -> {
                row.put("notional",             textOr(bd, "notional_dollars", "$?"));
                row.put("levels",               intOr(bd, "distinct_levels", 0));
                row.put("shares",               textOr(bd, "total_shares", "?"));
                row.put("slippage_bps",         textOr(bd, "slippage_bps", "?") + " bps "
                                                  + textOr(bd, "slippage_direction", ""));
                row.put("effective_spread_bps", textOr(bd, "effective_spread_bps", "?"));
                row.put("book_depth",           textOr(bd, "book_depth_imbalance_class", ""));
                row.put("start_et",             appendEt(textOr(bd, "start_et", "")));
                row.put("phase",                textOr(bd, "event_phase_label", ""));
            }
            case "iceberg" -> {
                row.put("fills",          intOr(bd, "fills", 0));
                row.put("total_shares",   textOr(bd, "total_shares", "?"));
                row.put("display_ratio",  textOr(bd, "display_ratio_pct", "?") + "%");
                row.put("refill_cadence", textOr(bd, "refill_cadence_class", ""));
                row.put("duration",       textOr(bd, "duration_humanized", ""));
                row.put("price",          textOr(bd, "price_dollars", "?"));
                row.put("start_et",       appendEt(textOr(bd, "start_et", "")));
                row.put("phase",          textOr(bd, "event_phase_label", ""));
            }
            case "layering" -> {
                row.put("orders",               intOr(bd, "orders", 0));
                row.put("distinct_levels",      intOr(bd, "distinct_levels", 0));
                row.put("total_shares",         textOr(bd, "total_shares", "?"));
                row.put("side",                 textOr(bd, "side", ""));
                row.put("depth_from_touch_bps", textOr(bd, "depth_from_touch_near_bps", "?")
                                                  + "-" + textOr(bd, "depth_from_touch_far_bps", "?"));
                row.put("median_lifetime_ms",   textOr(bd, "median_lifetime_ms", "?"));
                row.put("burstiness",           textOr(bd, "burstiness_class", ""));
                row.put("order_to_trade",       textOr(bd, "order_to_trade_phrase",
                                                  textOr(bd, "order_to_trade_ratio", "?")));
                row.put("phase",                textOr(bd, "event_phase_label", ""));
            }
            case "post_cancel_cluster" -> {
                row.put("orders",                intOr(bd, "orders", 0));
                row.put("total_shares",          textOr(bd, "total_shares", "?"));
                row.put("side",                  textOr(bd, "side", ""));
                row.put("median_lifetime_ms",    textOr(bd, "median_lifetime_ms", "?"));
                row.put("burstiness",            textOr(bd, "burstiness_class", ""));
                row.put("order_to_trade",        textOr(bd, "order_to_trade_phrase",
                                                  textOr(bd, "order_to_trade_ratio", "?")));
                row.put("phase",                 textOr(bd, "event_phase_label", ""));
            }
            case "liquidity_withdrawal" -> {
                row.put("deletes",            textOr(bd, "deletes", "?"));
                row.put("rate_per_sec",       textOr(bd, "rate_per_sec", "?"));
                row.put("side_class",         textOr(bd, "withdrawal_side_class", ""));
                row.put("pct_book_removed",   textOr(bd, "pct_of_book_removed", "?") + "%");
                row.put("duration",           textOr(bd, "duration_humanized", ""));
                row.put("burst_intensity",    textOr(bd, "burst_intensity_class", ""));
                row.put("recovery",           textOr(bd, "recovery_seconds", "n/a"));
                row.put("phase",              textOr(bd, "event_phase_label", ""));
            }
            case "volume_deviation" -> {
                row.put("deviation_x",      textOr(bd, "deviation_x", "?") + "x");
                row.put("percentile_rank",  textOr(bd, "percentile_rank", "?"));
                row.put("today_volume",     textOr(bd, "today_volume", "?"));
                row.put("baseline_median",  textOr(bd, "baseline_median_volume", "?"));
                row.put("regime_shift",     textOr(bd, "volume_regime_shift", "?"));
            }
            case "time_in_book_drift" -> {
                row.put("drift_x",                  textOr(bd, "drift_x", "?") + "x");
                row.put("direction",                textOr(bd, "drift_direction", ""));
                row.put("today_avg_lifetime",       textOr(bd, "today_avg_lifetime", "?"));
                row.put("baseline_median_lifetime", textOr(bd, "baseline_median_lifetime", "?"));
                row.put("order_count",              intOr(bd, "order_count", 0));
            }
            default -> {
                // unknown scorer — emit raw symbol + nothing else
            }
        }
        return row;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // day_summary: aggregate counts + top symbols + concentration metrics
    // ═══════════════════════════════════════════════════════════════════════

    private static ObjectNode buildDaySummary(final Connection conn,
                                                final LocalDate tradingDate,
                                                final ObjectMapper json) throws SQLException {
        ObjectNode out = json.createObjectNode();

        // Total scored + narrated event counts.
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT (SELECT COUNT(*) FROM scored_events WHERE trading_date=?) AS scored, "
                  + "(SELECT COUNT(*) FROM selected_events WHERE trading_date=?) AS selected")) {
            st.setObject(1, tradingDate);
            st.setObject(2, tradingDate);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    out.put("total_scored_events",   rs.getLong("scored"));
                    out.put("total_narrated_events", rs.getLong("selected"));
                }
            }
        }

        // By scorer.
        ObjectNode byScorer = json.createObjectNode();
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT scorer_id, COUNT(*) FROM selected_events "
                  + "WHERE trading_date=? GROUP BY scorer_id ORDER BY scorer_id")) {
            st.setObject(1, tradingDate);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) byScorer.put(rs.getString(1), rs.getLong(2));
            }
        }
        out.set("by_scorer", byScorer);

        // By session phase (categorical).
        ObjectNode bySession = json.createObjectNode();
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT COALESCE(breakdown->>'event_session_phase', 'unknown') AS phase, COUNT(*) "
                  + "FROM selected_events WHERE trading_date=? GROUP BY phase ORDER BY phase")) {
            st.setObject(1, tradingDate);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) bySession.put(rs.getString(1), rs.getLong(2));
            }
        }
        out.set("by_session_phase", bySession);

        // Top symbols by event count.
        ArrayNode topSymbols = json.createArrayNode();
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT symbol, COUNT(*) AS events FROM selected_events "
                  + "WHERE trading_date=? GROUP BY symbol ORDER BY events DESC LIMIT ?")) {
            st.setObject(1, tradingDate);
            st.setInt(2, TOP_SYMBOLS_LIMIT);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    ObjectNode row = json.createObjectNode();
                    row.put("symbol", rs.getString(1));
                    row.put("events", rs.getLong(2));
                    topSymbols.add(row);
                }
            }
        }
        out.set("top_symbols", topSymbols);

        // HHI on per-symbol event share. Pure SQL via window function.
        try (PreparedStatement st = conn.prepareStatement("""
                WITH counts AS (
                    SELECT symbol, COUNT(*)::float AS n FROM selected_events
                     WHERE trading_date=? GROUP BY symbol
                ),
                total AS (SELECT SUM(n) AS t FROM counts)
                SELECT SUM((n / t.t) * (n / t.t)) AS hhi FROM counts, total t
                """)) {
            st.setObject(1, tradingDate);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next() && rs.getObject("hhi") != null) {
                    out.put("symbol_concentration_hhi", BreakdownFmt.round(rs.getDouble("hhi"), 3));
                }
            }
        }

        // Scorer mix entropy.
        try (PreparedStatement st = conn.prepareStatement("""
                WITH counts AS (
                    SELECT scorer_id, COUNT(*)::float AS n FROM selected_events
                     WHERE trading_date=? GROUP BY scorer_id
                ),
                total AS (SELECT SUM(n) AS t FROM counts)
                SELECT -SUM((n / t.t) * LN(n / t.t)) AS entropy FROM counts, total t
                """)) {
            st.setObject(1, tradingDate);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next() && rs.getObject("entropy") != null) {
                    out.put("scorer_mix_entropy", BreakdownFmt.round(rs.getDouble("entropy"), 2));
                }
            }
        }

        return out;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // notable_extremes: superlatives across the day
    // ═══════════════════════════════════════════════════════════════════════

    private static ObjectNode buildNotableExtremes(final Connection conn,
                                                     final LocalDate tradingDate,
                                                     final ObjectMapper json) throws SQLException {
        ObjectNode out = json.createObjectNode();

        // Each extreme: find the scored event with the highest value of the
        // pre-formatted field, return symbol + value. Pure SQL on breakdown JSON.
        addExtreme(out, conn, tradingDate, json,
                "largest_notional_block", "large_trade",
                "(breakdown->>'notional_million_dollars')::float", "notional_dollars",
                "$ millions");
        addExtreme(out, conn, tradingDate, json,
                "largest_notional_sweep", "sweep",
                "(breakdown->>'notional_million_dollars')::float", "notional_dollars",
                "$ millions");
        addExtreme(out, conn, tradingDate, json,
                "longest_halt", "halt",
                "(breakdown->>'halt_duration_seconds')::float", "halt_duration",
                "duration");
        addExtreme(out, conn, tradingDate, json,
                "largest_pct_depth_removed", "liquidity_withdrawal",
                "(breakdown->>'pct_of_book_removed')::float", "pct_of_book_removed",
                "% of book");
        addExtreme(out, conn, tradingDate, json,
                "highest_volume_deviation", "volume_deviation",
                "(breakdown->>'deviation_x')::float", "deviation_x",
                "x trailing median");
        addExtreme(out, conn, tradingDate, json,
                "biggest_lifetime_drift", "time_in_book_drift",
                "(breakdown->>'drift_x')::float", "drift_x",
                "x");
        addExtreme(out, conn, tradingDate, json,
                "most_orders_in_layering", "layering",
                "(breakdown->>'orders')::float", "orders",
                "orders");
        addExtreme(out, conn, tradingDate, json,
                "most_orders_in_post_cancel", "post_cancel_cluster",
                "(breakdown->>'orders')::float", "orders",
                "orders");
        addExtreme(out, conn, tradingDate, json,
                "most_fills_iceberg", "iceberg",
                "(breakdown->>'fills')::float", "fills",
                "fills");
        addExtreme(out, conn, tradingDate, json,
                "deepest_sweep_levels", "sweep",
                "(breakdown->>'distinct_levels')::float", "distinct_levels",
                "levels");

        return out;
    }

    private static void addExtreme(final ObjectNode parent,
                                    final Connection conn,
                                    final LocalDate tradingDate,
                                    final ObjectMapper json,
                                    final String key,
                                    final String scorerId,
                                    final String orderExpr,
                                    final String valueField,
                                    final String unit) throws SQLException {
        String sql = "SELECT symbol, breakdown->>'" + valueField + "' AS v, "
                + "      breakdown->>'company_name' AS company "
                + "  FROM selected_events "
                + " WHERE trading_date=? AND scorer_id=? "
                + "       AND " + orderExpr + " IS NOT NULL "
                + " ORDER BY " + orderExpr + " DESC NULLS LAST "
                + " LIMIT 1";
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, tradingDate);
            st.setString(2, scorerId);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    ObjectNode row = json.createObjectNode();
                    row.put("symbol",   rs.getString("symbol"));
                    String company = rs.getString("company");
                    if (company != null) row.put("company_name", company);
                    row.put("value",    rs.getString("v"));
                    row.put("unit",     unit);
                    row.put("scorer",   scorerId);
                    parent.set(key, row);
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // helpers
    // ═══════════════════════════════════════════════════════════════════════

    private static String textOr(final ObjectNode bd, final String key, final String def) {
        if (bd.has(key) && !bd.get(key).isNull()) return bd.get(key).asText();
        return def;
    }

    private static int intOr(final ObjectNode bd, final String key, final int def) {
        if (bd.has(key) && bd.get(key).canConvertToInt()) return bd.get(key).asInt();
        return def;
    }

    private static String appendEt(final String hhmm) {
        if (hhmm == null || hhmm.isEmpty()) return "";
        return hhmm + " ET";
    }
}
