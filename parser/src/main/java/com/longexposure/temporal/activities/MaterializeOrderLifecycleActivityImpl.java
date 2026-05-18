package com.longexposure.temporal.activities;

import com.longexposure.storage.SchemaManager;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class MaterializeOrderLifecycleActivityImpl implements MaterializeOrderLifecycleActivity {

    private static final Logger LOG = LoggerFactory.getLogger(MaterializeOrderLifecycleActivityImpl.class);

    @Override
    public long materializeOrderLifecycle(final LocalDate tradingDate) {
        ActivityExecutionContext actx = Activity.getExecutionContext();
        LOG.info("materialize start  date={}", tradingDate);
        long t0 = System.nanoTime();

        // Background heartbeat — the JOIN runs for ≥ 5 min with no row
        // yields, so the per-statement default heartbeat from JDBC isn't
        // sufficient.
        ScheduledExecutorService keepAlive = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "materialize-lifecycle-heartbeat");
            t.setDaemon(true);
            return t;
        });
        AtomicReference<String> currentStage = new AtomicReference<>("starting");
        keepAlive.scheduleAtFixedRate(
                () -> {
                    try { actx.heartbeat("keep_alive:" + currentStage.get()); } catch (Exception ignored) {}
                },
                60, 60, TimeUnit.SECONDS);

        long rowsWritten;
        try (Connection conn = openConnection()) {
            // Idempotent schema apply — works whether or not this date has
            // been parsed before, and ensures order_lifecycle exists when
            // the activity runs standalone via MaterializeWorkflow.
            SchemaManager.apply(conn);
            conn.setAutoCommit(false);

            // Lift work_mem + force hash join planning to fit the 13 GB
            // hash table. work_mem='4GB' × hash_mem_multiplier=2 = 8 GB
            // per worker × 16 workers = 128 GB peak — bounded by the
            // postgres container's 48 GB mem_limit (Postgres won't
            // actually allocate that much unless every worker fills its
            // budget, which it won't for a single JOIN). Survives only
            // for this transaction.
            try (Statement st = conn.createStatement()) {
                st.execute("SET work_mem = '4GB'");
                st.execute("SET max_parallel_workers_per_gather = 16");
            }

            currentStage.set("preclean");
            long deleted;
            try (PreparedStatement st = conn.prepareStatement(
                    "DELETE FROM order_lifecycle WHERE trading_date = ?")) {
                st.setObject(1, tradingDate);
                deleted = st.executeLargeUpdate();
            }
            conn.commit();
            LOG.info("materialize pre-clean  date={} rows_deleted={}", tradingDate, deleted);
            actx.heartbeat("preclean_done:" + deleted);

            currentStage.set("materialize_join");
            rowsWritten = doMaterialize(conn, tradingDate);
            conn.commit();
        } catch (Exception e) {
            throw new RuntimeException("materializeOrderLifecycle failed for date=" + tradingDate, e);
        } finally {
            keepAlive.shutdownNow();
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        LOG.info("materialize done  date={} rows_written={} elapsed_ms={}",
                tradingDate, rowsWritten, elapsedMs);
        return rowsWritten;
    }

    /**
     * INSERT ... SELECT joining orders_add to orders_delete and the
     * latest orders_executed per order_id. Orders that survive end-of-
     * session land with delete_ts=NULL, execute_ts=NULL,
     * terminal_state='open'.
     *
     * <p>Trading-date bound on every input table so the JOIN doesn't
     * scan the full hypertable. The date range derives from {@code
     * tradingDate} interpreted as a UTC day boundary — matches how rows
     * are stored (we write TIMESTAMPTZ with the wire-format UTC
     * timestamp).
     *
     * <p>order_id is unique per session per IEX spec, so the JOIN
     * predicate is just {@code (a.order_id = d.order_id AND a.feed_source
     * = d.feed_source)} — no symbol equality check (it's redundant and
     * adds ~3 GB to the hash table at 162 M rows).
     */
    private static long doMaterialize(final Connection conn, final LocalDate tradingDate) throws Exception {
        // Trading-day boundary in UTC. Stored ts values are wire UTC; one
        // ET trading session (09:30–16:00) sits entirely inside one UTC
        // calendar day (13:30–20:00 UTC), so this filter captures the
        // session plus pre/post-market.
        //
        // Build "last execute per order_id" in a CTE first (~2.4 M
        // orders_executed rows for a day → small, fast). Then the main
        // INSERT...SELECT does TWO regular hash JOINs (orders_add vs
        // orders_delete, orders_add vs the small CTE). The CTE avoids
        // the bug shape of doing a per-row LATERAL lookup 162 M times.
        //
        // For orders_delete: per IEX DEEP+ spec, order_id is unique per
        // session, so one Add has at most one Delete. The LEFT JOIN with
        // (order_id, feed_source) is exact, not a duplicate-row trap.
        String sql = """
                WITH last_exec AS (
                    SELECT DISTINCT ON (order_id, feed_source)
                           order_id,
                           feed_source,
                           ts        AS exec_ts,
                           ts_nanos  AS exec_ts_nanos
                    FROM orders_executed
                    WHERE ts >= ? AND ts < ?
                      AND feed_source = 'DPLS'
                    ORDER BY order_id, feed_source, ts DESC
                )
                INSERT INTO order_lifecycle (
                    trading_date, symbol, order_id, side,
                    add_ts, add_ts_nanos, add_price_raw, add_size,
                    delete_ts, execute_ts, terminal_state, lifetime_ns,
                    feed_source
                )
                SELECT
                    ?::date AS trading_date,
                    a.symbol,
                    a.order_id,
                    a.side,
                    a.ts        AS add_ts,
                    a.ts_nanos  AS add_ts_nanos,
                    a.price_raw AS add_price_raw,
                    a.size      AS add_size,
                    d.ts        AS delete_ts,
                    e.exec_ts   AS execute_ts,
                    CASE
                        WHEN d.ts IS NOT NULL      THEN 'deleted'
                        WHEN e.exec_ts IS NOT NULL THEN 'executed'
                        ELSE 'open'
                    END AS terminal_state,
                    CASE
                        WHEN d.ts IS NOT NULL      THEN (d.ts_nanos      - a.ts_nanos)
                        WHEN e.exec_ts IS NOT NULL THEN (e.exec_ts_nanos - a.ts_nanos)
                        ELSE NULL
                    END AS lifetime_ns,
                    a.feed_source
                FROM orders_add a
                LEFT JOIN orders_delete d
                       ON d.order_id    = a.order_id
                      AND d.feed_source = a.feed_source
                      AND d.ts >= ? AND d.ts < ?
                LEFT JOIN last_exec e
                       ON e.order_id    = a.order_id
                      AND e.feed_source = a.feed_source
                WHERE a.ts >= ? AND a.ts < ?
                  AND a.feed_source = 'DPLS'
                """;

        try (PreparedStatement st = conn.prepareStatement(sql)) {
            java.sql.Date date = java.sql.Date.valueOf(tradingDate);
            java.sql.Timestamp lower = java.sql.Timestamp.valueOf(tradingDate.atStartOfDay());
            java.sql.Timestamp upper = java.sql.Timestamp.valueOf(tradingDate.plusDays(1).atStartOfDay());

            // 7 params: orders_executed window (CTE), trading_date,
            // orders_delete window, orders_add window.
            st.setTimestamp(1, lower); st.setTimestamp(2, upper);  // CTE window
            st.setDate(3, date);                                    // trading_date literal
            st.setTimestamp(4, lower); st.setTimestamp(5, upper);  // orders_delete window
            st.setTimestamp(6, lower); st.setTimestamp(7, upper);  // orders_add window

            return st.executeLargeUpdate();
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
}
