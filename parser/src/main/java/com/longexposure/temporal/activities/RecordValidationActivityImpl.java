package com.longexposure.temporal.activities;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.LocalDate;

public final class RecordValidationActivityImpl implements RecordValidationActivity {

    private static final Logger LOG = LoggerFactory.getLogger(RecordValidationActivityImpl.class);

    /** BBO-leg pass threshold. Empirical floor across recent days is ~99.4%. */
    private static final double BBO_THRESHOLD = 0.99;

    /** Price-level leg pass threshold. Should be ~100% (modulo same-ns multi-txn). */
    private static final double PRICE_LEVEL_THRESHOLD = 0.9999;

    private final ObjectMapper json = new ObjectMapper();

    @Override
    public Result record(
            final LocalDate tradingDate,
            final ValidationLegResult dplsDeep,
            final ValidationLegResult dplsTops,
            final ValidationLegResult deepTops) {

        ObjectNode notes = json.createObjectNode();
        long totalElapsed = 0;
        if (dplsDeep != null) {
            notes.put("dpls_deep_compared", dplsDeep.compared());
            notes.put("dpls_deep_matched", dplsDeep.matched());
            notes.put("dpls_deep_mismatched", dplsDeep.mismatched());
            totalElapsed = Math.max(totalElapsed, dplsDeep.elapsedMs());
        }
        if (dplsTops != null) {
            notes.put("dpls_tops_compared", dplsTops.compared());
            notes.put("dpls_tops_matched", dplsTops.matched());
            notes.put("dpls_tops_mismatched", dplsTops.mismatched());
            totalElapsed = Math.max(totalElapsed, dplsTops.elapsedMs());
        }
        if (deepTops != null) {
            notes.put("deep_tops_compared", deepTops.compared());
            notes.put("deep_tops_matched", deepTops.matched());
            notes.put("deep_tops_mismatched", deepTops.mismatched());
            totalElapsed = Math.max(totalElapsed, deepTops.elapsedMs());
        }

        String status = classify(dplsDeep, dplsTops, deepTops);
        String notesJson = notes.toString();
        long elapsedSeconds = totalElapsed / 1000L;

        try (Connection conn = openConnection()) {
            upsert(conn, tradingDate, dplsDeep, dplsTops, deepTops,
                    (int) elapsedSeconds, status, notesJson);
            LOG.info("validation_runs upsert ok  date={} status={} dpls_deep={} dpls_tops={} deep_tops={}",
                    tradingDate, status,
                    dplsDeep != null ? dplsDeep.matchRate() : null,
                    dplsTops != null ? dplsTops.matchRate() : null,
                    deepTops != null ? deepTops.matchRate() : null);
        } catch (Exception e) {
            throw new RuntimeException("validation_runs upsert failed", e);
        }

        return new Result(status, notesJson, elapsedSeconds);
    }

    private static String classify(final ValidationLegResult dplsDeep,
                                   final ValidationLegResult dplsTops,
                                   final ValidationLegResult deepTops) {
        if (dplsDeep == null || dplsTops == null || deepTops == null) return "failed";
        if (dplsDeep.matchRate() < PRICE_LEVEL_THRESHOLD) return "below_threshold";
        if (dplsTops.matchRate() < BBO_THRESHOLD || deepTops.matchRate() < BBO_THRESHOLD) return "below_threshold";
        return "passed";
    }

    private static void upsert(final Connection conn,
                               final LocalDate date,
                               final ValidationLegResult dplsDeep,
                               final ValidationLegResult dplsTops,
                               final ValidationLegResult deepTops,
                               final int elapsedSeconds,
                               final String status,
                               final String notesJson) throws Exception {
        String sql = "INSERT INTO validation_runs ("
                + "trading_date, dpls_tops_match_pct, deep_tops_match_pct, "
                + "dpls_deep_match_pct, trade_volume_match, elapsed_seconds, status, notes) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb) "
                + "ON CONFLICT (trading_date) DO UPDATE SET "
                + "run_at = NOW(), "
                + "dpls_tops_match_pct = EXCLUDED.dpls_tops_match_pct, "
                + "deep_tops_match_pct = EXCLUDED.deep_tops_match_pct, "
                + "dpls_deep_match_pct = EXCLUDED.dpls_deep_match_pct, "
                + "trade_volume_match = EXCLUDED.trade_volume_match, "
                + "elapsed_seconds = EXCLUDED.elapsed_seconds, "
                + "status = EXCLUDED.status, "
                + "notes = EXCLUDED.notes";
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, date);
            if (dplsTops != null) st.setDouble(2, dplsTops.matchRate()); else st.setNull(2, Types.DOUBLE);
            if (deepTops != null) st.setDouble(3, deepTops.matchRate()); else st.setNull(3, Types.DOUBLE);
            if (dplsDeep != null) st.setDouble(4, dplsDeep.matchRate()); else st.setNull(4, Types.DOUBLE);
            st.setNull(5, Types.BOOLEAN);
            st.setInt(6, elapsedSeconds);
            st.setString(7, status);
            st.setString(8, notesJson);
            st.executeUpdate();
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
