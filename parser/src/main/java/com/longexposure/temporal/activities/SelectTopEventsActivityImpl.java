package com.longexposure.temporal.activities;

import com.longexposure.storage.SchemaManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.LocalDate;

public final class SelectTopEventsActivityImpl implements SelectTopEventsActivity {

    private static final Logger LOG = LoggerFactory.getLogger(SelectTopEventsActivityImpl.class);

    /**
     * Selection rule: percentile rank within each scorer's daily events,
     * with a floor and ceiling on count. For each scorer:
     *
     * <pre>
     *   selected_count = clamp(
     *       round(PERCENTILE_TOP * scorer_event_count),
     *       PER_SCORER_FLOOR,
     *       PER_SCORER_CEILING
     *   )
     * </pre>
     *
     * <p>Then we take the top-N events of that scorer by score
     * descending. The percentile is a "what fraction of this scorer's
     * events deserve narration" knob; floor + ceiling bound the count
     * on tiny-distribution and huge-distribution scorers respectively.
     *
     * <p>Why percentile-of-rank instead of percentile-of-score: scores
     * have wildly different distributions across scorers (linear for
     * halt, log-scale for large_trade, multiplicative for pattern
     * scorers). Rank percentile is scorer-agnostic.
     *
     * <p>v1 numbers picked to produce ~100-200 narrations/day on the
     * 2026-05-08 reference set. Tune by observation; this is one of
     * the few hardcoded knobs the project deliberately retains.
     */
    private static final double PERCENTILE_TOP    = 0.05;   // top 5% by rank
    private static final int    PER_SCORER_FLOOR  = 1;      // never drop a scorer's top
    private static final int    PER_SCORER_CEILING = 30;    // never let a scorer dominate

    @Override
    public long selectTopEvents(final LocalDate tradingDate) {
        LOG.info("select start  date={} percentile_top={} floor={} ceiling={}",
                tradingDate, PERCENTILE_TOP, PER_SCORER_FLOOR, PER_SCORER_CEILING);

        long total;
        try (Connection conn = openConnection()) {
            SchemaManager.apply(conn);
            conn.setAutoCommit(false);

            try (PreparedStatement st = conn.prepareStatement(
                    "DELETE FROM selected_events WHERE trading_date = ?")) {
                st.setObject(1, tradingDate);
                long deleted = st.executeLargeUpdate();
                LOG.info("select pre-clean  date={} rows_deleted={}", tradingDate, deleted);
            }
            conn.commit();

            total = insertSelected(conn, tradingDate);
            conn.commit();
        } catch (Exception e) {
            throw new RuntimeException("selectTopEvents failed for date=" + tradingDate, e);
        }

        LOG.info("select done  date={} total_selected={}", tradingDate, total);
        return total;
    }

    /**
     * Single SQL pass that:
     * <ol>
     *   <li>Filters {@code scored_events} for the date, excluding subsumed
     *       constituents (those are already represented by a
     *       {@code combined} row).
     *   <li>Ranks events within each scorer by score descending.
     *   <li>Computes each scorer's selection budget as
     *       {@code clamp(round(percentile × count), floor, ceiling)}.
     *   <li>Selects rows whose rank is within their scorer's budget.
     * </ol>
     *
     * <p>The CTE chain reads top-to-bottom as the rule.
     */
    private long insertSelected(final Connection conn, final LocalDate date) throws Exception {
        String sql = """
                WITH scoped AS (
                    -- All non-subsumed events for the date. Subsumed
                    -- constituents are already represented by their
                    -- 'combined' parent row.
                    SELECT event_id, trading_date, symbol, ts, ts_end, scorer_id,
                           score, breakdown, source_refs
                    FROM scored_events
                    WHERE trading_date = ?
                      AND subsumed_by_event_id IS NULL
                ),
                ranked AS (
                    SELECT s.*,
                           row_number() OVER (PARTITION BY scorer_id ORDER BY score DESC, ts) AS rk,
                           count(*)    OVER (PARTITION BY scorer_id) AS scorer_count
                    FROM scoped s
                ),
                budgeted AS (
                    -- Compute per-scorer budget = clamp(round(p × count), floor, ceiling)
                    SELECT *,
                           GREATEST(?, LEAST(?, CEIL(? * scorer_count)::int)) AS scorer_budget
                    FROM ranked
                ),
                qualifying AS (
                    SELECT *
                    FROM budgeted
                    WHERE rk <= scorer_budget
                )
                INSERT INTO selected_events (
                    event_id, trading_date, symbol, ts, ts_end, scorer_id,
                    score, breakdown, source_refs, narration_rank
                )
                SELECT event_id, trading_date, symbol, ts, ts_end, scorer_id,
                       score, breakdown, source_refs, rk AS narration_rank
                FROM qualifying
                ORDER BY scorer_id, rk
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, date);
            st.setInt(2, PER_SCORER_FLOOR);
            st.setInt(3, PER_SCORER_CEILING);
            st.setDouble(4, PERCENTILE_TOP);
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
