package com.longexposure.temporal.activities;

import com.longexposure.storage.SchemaManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Implementation of {@link RefreshBaselinesActivity}. Calls TimescaleDB's
 * {@code refresh_continuous_aggregate(cagg, window_start, window_end)} for the
 * single day {@code [tradingDate, tradingDate + 1)}.
 *
 * <p>{@code refresh_continuous_aggregate} cannot run inside an explicit
 * transaction, so this opens its own connection and leaves it at the default
 * autocommit=true (each statement is its own implicit transaction). The
 * {@code CALL} is therefore valid; the scoring activity's cursor transaction
 * is untouched.
 */
public final class RefreshBaselinesActivityImpl implements RefreshBaselinesActivity {

    private static final Logger LOG = LoggerFactory.getLogger(RefreshBaselinesActivityImpl.class);

    private static final String CAGG = "daily_volume_by_symbol";

    @Override
    public long refreshBaselines(final LocalDate tradingDate) {
        Timestamp start = Timestamp.from(tradingDate.atStartOfDay().toInstant(ZoneOffset.UTC));
        Timestamp end   = Timestamp.from(tradingDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        long t0 = System.nanoTime();
        try (Connection conn = openConnection()) {
            // Schema must exist (the cagg + its policy) before we can refresh it.
            // apply() leaves autoCommit=true, which is exactly what the CALL needs.
            SchemaManager.apply(conn);

            // refresh_continuous_aggregate's window args are the "any" pseudo-type,
            // so the JDBC driver can't infer bound-parameter types — explicit
            // casts are required (a bare `?` yields "could not determine data
            // type of parameter").
            try (PreparedStatement st = conn.prepareStatement(
                    "CALL refresh_continuous_aggregate(?::regclass, ?::timestamptz, ?::timestamptz)")) {
                st.setString(1, CAGG);
                st.setTimestamp(2, start);
                st.setTimestamp(3, end);
                st.execute();
            }
        } catch (Exception e) {
            throw new RuntimeException("refresh baselines failed for date=" + tradingDate, e);
        }

        long ms = (System.nanoTime() - t0) / 1_000_000L;
        LOG.info("baselines refreshed  cagg={} date={} elapsed_ms={}", CAGG, tradingDate, ms);
        return 1L;
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
