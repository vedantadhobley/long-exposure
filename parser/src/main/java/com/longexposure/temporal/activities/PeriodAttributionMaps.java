package com.longexposure.temporal.activities;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

/**
 * Period-aggregated attribution truth maps from {@code selected_events}.
 * Shared between the three AGGREGATE tiers (week / quarter / year) — same
 * SQL shape, different date ranges. The maps feed
 * {@link com.longexposure.narration.AttributionVerifier} via the 5-arg
 * {@link com.longexposure.narration.SynthesisVerifier#verify} overload so
 * each rollup tier can validate symbol-attributed count claims like
 * "TQQQ had N events this {week,quarter,year}" against actual cross-day
 * sums.
 *
 * <p>Extracted 2026-05-28 evening to consolidate the duplicated SQL across
 * the three AggregateXxxActivityImpl classes. Same package-private scope
 * — no public API surface; only the three activities call into it.
 */
final class PeriodAttributionMaps {

    private PeriodAttributionMaps() {}

    /**
     * Populate {@code outBySymbolScorer} (per-symbol per-scorer counts) and
     * {@code outBySymbolTotal} (per-symbol total counts) by summing
     * {@code selected_events} rows over a half-open date range
     * {@code [periodStart, periodEndExclusive)}.
     *
     * <p>Mirrors the per-day map-building pattern in
     * {@link SynthesizeDayActivityImpl#computeDayAggregates}, just summed
     * across multiple days. Out-params keep the call site cheap — no
     * allocations for return wrappers.
     *
     * @param conn                open Postgres connection
     * @param periodStart         inclusive start date
     * @param periodEndExclusive  exclusive end date
     * @param outBySymbolScorer   (out) symbol → (scorer → count)
     * @param outBySymbolTotal    (out) symbol → total count
     */
    static void load(final Connection conn,
                     final LocalDate periodStart,
                     final LocalDate periodEndExclusive,
                     final Map<String, Map<String, Integer>> outBySymbolScorer,
                     final Map<String, Integer> outBySymbolTotal) throws Exception {
        String sql = """
                SELECT symbol, scorer_id, COUNT(*) AS cnt
                FROM selected_events
                WHERE trading_date >= ? AND trading_date < ?
                  AND symbol IS NOT NULL
                GROUP BY symbol, scorer_id
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, periodStart);
            st.setObject(2, periodEndExclusive);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    String symbol = rs.getString(1);
                    String scorerId = rs.getString(2);
                    int cnt = rs.getInt(3);
                    outBySymbolScorer
                            .computeIfAbsent(symbol, k -> new TreeMap<>())
                            .merge(scorerId, cnt, Integer::sum);
                    outBySymbolTotal.merge(symbol, cnt, Integer::sum);
                }
            }
        }
    }
}
