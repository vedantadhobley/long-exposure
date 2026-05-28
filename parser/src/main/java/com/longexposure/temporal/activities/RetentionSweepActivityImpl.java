package com.longexposure.temporal.activities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

/**
 * Implementation of {@link RetentionSweepActivity}.
 *
 * <p>For DPLS-only hypertables (including the derived
 * {@code order_lifecycle}), uses TimescaleDB's
 * {@code drop_chunks(table_name, older_than)} — O(chunks), independent
 * of row count. For tables shared with TOPS (the validation oracle), a
 * per-row DELETE filtered by {@code feed_source='DPLS'} avoids clobbering
 * the TOPS rows. The derived standard tables ({@code scored_events},
 * {@code selected_events}) are DELETEd by {@code trading_date}.
 *
 * <p>The drop boundary is week-aligned — see
 * {@link #weekBoundary(LocalDate, int)} and the policy description on
 * {@link RetentionSweepActivity}.
 */
public final class RetentionSweepActivityImpl implements RetentionSweepActivity {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionSweepActivityImpl.class);

    /** DPLS-only hypertables — safe to chunk-drop (includes the derived lifecycle table). */
    private static final String[] DPLS_ONLY_TABLES = {
            "orders_add", "orders_modify", "orders_delete", "orders_executed",
            "clear_books", "order_lifecycle"
    };

    /** Tables shared with TOPS — must DELETE per-row by feed_source. */
    private static final String[] SHARED_TABLES = {
            "trades", "trade_breaks", "quotes", "status_events", "auction_info",
            "official_prices", "securities", "retail_liquidity"
    };

    /** Derived standard (non-hypertable) tables — DELETE by trading_date. */
    private static final String[] DERIVED_DATE_TABLES = {
            "scored_events", "selected_events"
    };

    // INVARIANT (do not break): the sweep must NEVER drop the durable per-symbol
    // baselines — the `daily_volume_by_symbol` continuous aggregate OR the
    // `daily_lifetime_by_symbol` table (nor any future baseline store). Both
    // hold a rolling ~1 year of per-symbol daily baselines that intentionally
    // OUTLIVE the 2-week wire retention (a cagg decouples from its source once
    // materialized; daily_lifetime is a plain table the materialize step
    // upserts into — see schema.sql + docs/tiered-baselines-design.md §2.2).
    // They are read by the inter-day scorers (VolumeDeviation, TimeInBookDrift).
    // Adding either to any list above would silently destroy that history.

    /**
     * Week-aligned drop boundary: the Monday of {@code cutoffDate}'s week,
     * minus {@code retainWeeks} weeks. Everything strictly before the
     * returned date is dropped. {@code retainWeeks=2} keeps the current
     * (partial) week plus the 2 most-recent completed weeks, so we never
     * hold fewer than 2 full completed weeks.
     *
     * <p>Pure + deterministic so it's unit-testable without a DB.
     */
    static LocalDate weekBoundary(final LocalDate cutoffDate, final int retainWeeks) {
        LocalDate currentMonday = cutoffDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return currentMonday.minusWeeks(retainWeeks);
    }

    @Override
    public SweepResult sweep(final LocalDate cutoffDate, final int retainWeeks) {
        LocalDate boundaryDate = weekBoundary(cutoffDate, retainWeeks);
        Timestamp olderThan = Timestamp.from(boundaryDate.atStartOfDay().toInstant(ZoneOffset.UTC));

        LOG.info("retention sweep start  cutoff={} retain_weeks={} drop_before={} (week-aligned)",
                cutoffDate, retainWeeks, boundaryDate);

        int chunksDropped = 0;
        long rowsDeleted = 0;

        try (Connection conn = openConnection()) {
            for (String table : DPLS_ONLY_TABLES) {
                try (PreparedStatement st = conn.prepareStatement(
                        "SELECT drop_chunks(?, ?)")) {
                    st.setString(1, table);
                    st.setTimestamp(2, olderThan);
                    try (ResultSet rs = st.executeQuery()) {
                        int dropped = 0;
                        while (rs.next()) dropped++;
                        chunksDropped += dropped;
                        if (dropped > 0) {
                            LOG.info("dropped chunks  table={} count={}", table, dropped);
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("drop_chunks failed  table={} err={}", table, e.getMessage());
                }
            }

            for (String table : SHARED_TABLES) {
                String sql = "DELETE FROM " + table
                        + " WHERE feed_source = 'DPLS' AND ts < ?";
                try (PreparedStatement st = conn.prepareStatement(sql)) {
                    st.setTimestamp(1, olderThan);
                    long deleted = st.executeLargeUpdate();
                    rowsDeleted += deleted;
                    if (deleted > 0) {
                        LOG.info("deleted shared rows  table={} count={}", table, deleted);
                    }
                } catch (Exception e) {
                    LOG.warn("shared-row delete failed  table={} err={}", table, e.getMessage());
                }
            }

            // Derived standard tables key off trading_date, not a ts column.
            for (String table : DERIVED_DATE_TABLES) {
                String sql = "DELETE FROM " + table + " WHERE trading_date < ?";
                try (PreparedStatement st = conn.prepareStatement(sql)) {
                    st.setObject(1, boundaryDate);
                    long deleted = st.executeLargeUpdate();
                    rowsDeleted += deleted;
                    if (deleted > 0) {
                        LOG.info("deleted derived rows  table={} count={}", table, deleted);
                    }
                } catch (Exception e) {
                    LOG.warn("derived-row delete failed  table={} err={}", table, e.getMessage());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("retention sweep failed", e);
        }

        LOG.info("retention sweep done  chunks_dropped={} rows_deleted={}",
                chunksDropped, rowsDeleted);
        return new SweepResult(chunksDropped, rowsDeleted);
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
}
