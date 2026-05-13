package com.longexposure.temporal.activities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Implementation of {@link RetentionSweepActivity}.
 *
 * <p>For each DPLS-only hypertable, uses TimescaleDB's
 * {@code drop_chunks(table_name, older_than)} to drop chunks older than
 * the cutoff. Chunk-level drop is O(chunks), independent of row count.
 *
 * <p>For tables shared with TOPS (the validation oracle), we use a
 * per-row DELETE filtered by {@code feed_source='DPLS'} to avoid
 * clobbering the TOPS rows. Slower but correct.
 */
public final class RetentionSweepActivityImpl implements RetentionSweepActivity {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionSweepActivityImpl.class);

    /** DPLS-only tables — safe to chunk-drop. */
    private static final String[] DPLS_ONLY_TABLES = {
            "orders_add", "orders_modify", "orders_delete", "orders_executed", "clear_books"
    };

    /** Tables shared with TOPS — must DELETE per-row by feed_source. */
    private static final String[] SHARED_TABLES = {
            "trades", "trade_breaks", "quotes", "status_events", "auction_info",
            "official_prices", "securities", "retail_liquidity"
    };

    @Override
    public SweepResult sweep(final LocalDate cutoffDate, final int retainDays) {
        LocalDate olderThanDate = cutoffDate.minusDays(retainDays);
        Timestamp olderThan = Timestamp.from(olderThanDate.atStartOfDay().toInstant(ZoneOffset.UTC));

        LOG.info("retention sweep start  cutoff={} retain_days={} older_than={}",
                cutoffDate, retainDays, olderThan);

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
