package com.longexposure.temporal.activities;

import com.longexposure.storage.SchemaManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.LocalDate;

/**
 * Implementation of {@link PruneStaleNarrationsActivity}.
 *
 * <p>The DELETE is one window-function CTE per table: row-number partitioned
 * by content-key, ordered by ({@code verifier_passed DESC, created_at DESC})
 * so the latest passing row gets rank 1 (or the latest overall if none
 * passed). Delete every row with rank &gt; 1.
 *
 * <p>Per-table failures are logged + survive — orphan cleanup is opportunistic,
 * not load-bearing.
 */
public final class PruneStaleNarrationsActivityImpl implements PruneStaleNarrationsActivity {

    private static final Logger LOG = LoggerFactory.getLogger(PruneStaleNarrationsActivityImpl.class);

    @Override
    public PruneResult pruneAll() {
        return prune(null);
    }

    @Override
    public PruneResult pruneDate(final LocalDate tradingDate) {
        return prune(tradingDate);
    }

    private PruneResult prune(final LocalDate tradingDateOrNull) {
        try (Connection conn = openConnection()) {
            SchemaManager.apply(conn);
            long n = pruneTable(conn, "narratives", "event_hash", tradingDateOrNull);
            long i = pruneTable(conn, "interpretations", "interpretation_hash", tradingDateOrNull);
            LOG.info("prune complete  date={} narratives_del={} interpretations_del={}",
                    tradingDateOrNull == null ? "ALL" : tradingDateOrNull, n, i);
            return new PruneResult(n, i);
        } catch (Exception e) {
            LOG.warn("prune failed  date={} err={}", tradingDateOrNull, e.getMessage());
            return new PruneResult(0, 0);
        }
    }

    /**
     * Window-function delete: row_number partitioned by content-key, ordered
     * so the latest verifier_passing row is rank 1. Drop rn>1.
     */
    private static long pruneTable(final Connection conn,
                                    final String table,
                                    final String pkColumn,
                                    final LocalDate tradingDateOrNull) {
        String dateFilter = tradingDateOrNull == null ? "" : " WHERE trading_date = ?";
        String sql = """
                WITH ranked AS (
                    SELECT %s AS pk,
                           row_number() OVER (
                               PARTITION BY trading_date, symbol, event_type, event_ts
                               ORDER BY verifier_passed DESC, created_at DESC
                           ) AS rn
                    FROM %s
                    %s
                )
                DELETE FROM %s t USING ranked r
                WHERE t.%s = r.pk AND r.rn > 1
                """.formatted(pkColumn, table, dateFilter, table, pkColumn);
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            if (tradingDateOrNull != null) st.setObject(1, tradingDateOrNull);
            int deleted = st.executeUpdate();
            LOG.info("prune {}  date={} deleted={}", table,
                    tradingDateOrNull == null ? "ALL" : tradingDateOrNull, deleted);
            return deleted;
        } catch (Exception e) {
            LOG.warn("prune {} failed  date={} err={}", table, tradingDateOrNull, e.getMessage());
            return 0;
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
