package com.longexposure.temporal.activities;

import com.longexposure.storage.SchemaManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SelectTopEventsActivityImpl implements SelectTopEventsActivity {

    private static final Logger LOG = LoggerFactory.getLogger(SelectTopEventsActivityImpl.class);

    /**
     * Per-scorer top-N caps. LinkedHashMap so log output is deterministic.
     *
     * <p>v1 starting points — informed by the 2026-05-08 scored_events
     * counts. Halts (105 raw) and large trades (149 raw) are inherently
     * narratable so we keep most. Pattern scorers fire 73K-343K raw
     * events; even the top 10 each are likely the genuinely-notable
     * ones with the rest being noise.
     */
    private static final Map<String, Integer> PER_SCORER_CAPS = new LinkedHashMap<>();
    static {
        PER_SCORER_CAPS.put("halt",                  20);
        PER_SCORER_CAPS.put("large_trade",           20);
        PER_SCORER_CAPS.put("sweep",                 10);
        PER_SCORER_CAPS.put("post_cancel_cluster",   10);
        PER_SCORER_CAPS.put("layering",              10);
        PER_SCORER_CAPS.put("iceberg",               10);
        PER_SCORER_CAPS.put("liquidity_withdrawal",  10);
    }

    @Override
    public long selectTopEvents(final LocalDate tradingDate) {
        LOG.info("select start  date={}", tradingDate);

        long total = 0;
        try (Connection conn = openConnection()) {
            SchemaManager.apply(conn);
            conn.setAutoCommit(false);

            // Pre-clean
            try (PreparedStatement st = conn.prepareStatement(
                    "DELETE FROM selected_events WHERE trading_date = ?")) {
                st.setObject(1, tradingDate);
                long deleted = st.executeLargeUpdate();
                LOG.info("select pre-clean  date={} rows_deleted={}", tradingDate, deleted);
            }
            conn.commit();

            // Per-scorer INSERT ... SELECT ... LIMIT N
            for (Map.Entry<String, Integer> entry : PER_SCORER_CAPS.entrySet()) {
                long n = insertTopForScorer(conn, tradingDate, entry.getKey(), entry.getValue());
                LOG.info("selected  scorer={} cap={} rows={}", entry.getKey(), entry.getValue(), n);
                total += n;
            }
            conn.commit();
        } catch (Exception e) {
            throw new RuntimeException("selectTopEvents failed for date=" + tradingDate, e);
        }

        LOG.info("select done  date={} total_selected={}", tradingDate, total);
        return total;
    }

    private long insertTopForScorer(final Connection conn,
                                    final LocalDate tradingDate,
                                    final String scorerId,
                                    final int cap) throws Exception {
        String sql = """
                INSERT INTO selected_events (
                    event_id, trading_date, symbol, ts, ts_end, scorer_id,
                    score, breakdown, source_refs, narration_rank
                )
                SELECT
                    event_id, trading_date, symbol, ts, ts_end, scorer_id,
                    score, breakdown, source_refs,
                    row_number() OVER (ORDER BY score DESC, ts) AS narration_rank
                FROM scored_events
                WHERE trading_date = ?
                  AND scorer_id    = ?
                ORDER BY score DESC, ts
                LIMIT ?
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, tradingDate);
            st.setString(2, scorerId);
            st.setInt(3, cap);
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
