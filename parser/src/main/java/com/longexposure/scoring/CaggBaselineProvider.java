package com.longexposure.scoring;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link BaselineProvider} backed by the {@code daily_volume_by_symbol}
 * continuous aggregate. Stateless apart from the borrowed JDBC connection —
 * does not close it (the scoring activity owns the connection's lifecycle).
 *
 * <p>Both queries are cheap: the cagg is one tiny row per symbol per day, so a
 * single-day snapshot and a windowed {@code percentile_cont(0.5)} median over
 * ~8.8k symbols × {@code windowDays} rows is sub-second.
 *
 * <p>The day key matches the cagg's {@code time_bucket(INTERVAL '1 day', ts)}
 * which buckets on the UTC midnight boundary, so a {@link LocalDate} is mapped
 * to its UTC start-of-day timestamp.
 */
public final class CaggBaselineProvider implements BaselineProvider {

    private final Connection conn;

    public CaggBaselineProvider(final Connection conn) {
        this.conn = conn;
    }

    @Override
    public Map<String, DayVolume> dayVolumes(final LocalDate day) {
        String sql = """
                SELECT symbol, total_volume, trade_count
                FROM daily_volume_by_symbol
                WHERE day = ?
                """;
        Map<String, DayVolume> out = new HashMap<>(16_384);
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setTimestamp(1, utcMidnight(day));
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("symbol"),
                            new DayVolume(rs.getLong("total_volume"), rs.getLong("trade_count")));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("dayVolumes query failed for day=" + day, e);
        }
        return out;
    }

    @Override
    public Map<String, TrailingVolume> trailingVolumeBaselines(final LocalDate day, final int windowDays) {
        // Median over prior days in [day - windowDays, day). percentile_cont(0.5)
        // is the median; computed over the small cagg so this is cheap.
        String sql = """
                SELECT symbol,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY total_volume) AS med_vol,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY trade_count)  AS med_tc,
                       count(*) AS days
                FROM daily_volume_by_symbol
                WHERE day >= ? AND day < ?
                GROUP BY symbol
                """;
        Map<String, TrailingVolume> out = new HashMap<>(16_384);
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setTimestamp(1, utcMidnight(day.minusDays(windowDays)));
            st.setTimestamp(2, utcMidnight(day));
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("symbol"),
                            new TrailingVolume(rs.getDouble("med_vol"),
                                               rs.getDouble("med_tc"),
                                               rs.getInt("days")));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("trailingVolumeBaselines query failed for day=" + day, e);
        }
        return out;
    }

    @Override
    public Map<String, double[]> trailingVolumeWindows(final LocalDate day, final int windowDays) {
        // Raw per-symbol daily volumes over [day - windowDays, day) — the caller
        // (VolumeDeviationScorer) derives median / MAD / percentile-rank from
        // these via the tested Analytics functions, rather than computing each
        // statistic in SQL (MAD has no Postgres builtin, and one array keeps the
        // stats consistent). Cheap: the cagg is one tiny row per symbol per day.
        String sql = """
                SELECT symbol, total_volume
                FROM daily_volume_by_symbol
                WHERE day >= ? AND day < ?
                ORDER BY symbol
                """;
        Map<String, List<Double>> acc = new HashMap<>(16_384);
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setTimestamp(1, utcMidnight(day.minusDays(windowDays)));
            st.setTimestamp(2, utcMidnight(day));
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    acc.computeIfAbsent(rs.getString("symbol"), k -> new ArrayList<>())
                       .add((double) rs.getLong("total_volume"));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("trailingVolumeWindows query failed for day=" + day, e);
        }
        Map<String, double[]> out = new HashMap<>(acc.size() * 2);
        for (Map.Entry<String, List<Double>> e : acc.entrySet()) {
            double[] arr = new double[e.getValue().size()];
            for (int i = 0; i < arr.length; i++) arr[i] = e.getValue().get(i);
            out.put(e.getKey(), arr);
        }
        return out;
    }

    // ─── Order-lifetime baselines (daily_lifetime_by_symbol) ─────────────────
    // This table uses a plain DATE `day` column (populated by the materialize
    // step), not the cagg's UTC-midnight time_bucket — so these bind LocalDate
    // directly rather than via utcMidnight().

    @Override
    public Map<String, DayLifetime> dayLifetimes(final LocalDate day) {
        String sql = "SELECT symbol, avg_lifetime_ns, order_count FROM daily_lifetime_by_symbol WHERE day = ?";
        Map<String, DayLifetime> out = new HashMap<>(16_384);
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, day);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("symbol"),
                            new DayLifetime(rs.getLong("avg_lifetime_ns"), rs.getLong("order_count")));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("dayLifetimes query failed for day=" + day, e);
        }
        return out;
    }

    @Override
    public Map<String, TrailingLifetime> trailingLifetimeBaselines(final LocalDate day, final int windowDays) {
        String sql = """
                SELECT symbol,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY avg_lifetime_ns) AS med_life,
                       count(*) AS days
                FROM daily_lifetime_by_symbol
                WHERE day >= ? AND day < ?
                GROUP BY symbol
                """;
        Map<String, TrailingLifetime> out = new HashMap<>(16_384);
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, day.minusDays(windowDays));
            st.setObject(2, day);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("symbol"),
                            new TrailingLifetime(rs.getDouble("med_life"), rs.getInt("days")));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("trailingLifetimeBaselines query failed for day=" + day, e);
        }
        return out;
    }

    @Override
    public Map<String, double[]> trailingLifetimeWindows(final LocalDate day, final int windowDays) {
        String sql = """
                SELECT symbol, avg_lifetime_ns
                FROM daily_lifetime_by_symbol
                WHERE day >= ? AND day < ?
                ORDER BY symbol
                """;
        Map<String, List<Double>> acc = new HashMap<>(16_384);
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, day.minusDays(windowDays));
            st.setObject(2, day);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    acc.computeIfAbsent(rs.getString("symbol"), k -> new ArrayList<>())
                       .add((double) rs.getLong("avg_lifetime_ns"));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("trailingLifetimeWindows query failed for day=" + day, e);
        }
        Map<String, double[]> out = new HashMap<>(acc.size() * 2);
        for (Map.Entry<String, List<Double>> e : acc.entrySet()) {
            double[] arr = new double[e.getValue().size()];
            for (int i = 0; i < arr.length; i++) arr[i] = e.getValue().get(i);
            out.put(e.getKey(), arr);
        }
        return out;
    }

    private static Timestamp utcMidnight(final LocalDate day) {
        return Timestamp.from(day.atStartOfDay().toInstant(ZoneOffset.UTC));
    }
}
