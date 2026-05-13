package com.longexposure.temporal.activities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.LocalDate;
import java.util.UUID;

public final class PipelineRunRecorderActivityImpl implements PipelineRunRecorderActivity {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineRunRecorderActivityImpl.class);

    @Override
    public boolean isAlreadyIngested(final LocalDate tradingDate) {
        String sql = "SELECT 1 FROM pipeline_runs "
                + "WHERE trading_date = ? AND feed_source = 'DPLS' AND status = 'ok' "
                + "LIMIT 1";
        try (Connection conn = openConnection();
             PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, tradingDate);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new RuntimeException("pipeline_runs lookup failed", e);
        }
    }

    @Override
    public String startRun(final LocalDate tradingDate) {
        String runId = UUID.randomUUID().toString();
        String sql = "INSERT INTO pipeline_runs (run_id, trading_date, feed_source, started_at, status) "
                + "VALUES (?::uuid, ?, 'DPLS', NOW(), 'running')";
        try (Connection conn = openConnection();
             PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, runId);
            st.setObject(2, tradingDate);
            st.executeUpdate();
            LOG.info("pipeline run started  run_id={} date={}", runId, tradingDate);
            return runId;
        } catch (Exception e) {
            throw new RuntimeException("pipeline_runs insert failed", e);
        }
    }

    @Override
    public void completeRun(final String runId, final String status,
                            final Long parserMessageCount, final String validatorStatus,
                            final String notesJson) {
        String sql = "UPDATE pipeline_runs SET "
                + "completed_at = NOW(), status = ?, "
                + "parser_message_count = ?, validator_status = ?, "
                + "notes = ?::jsonb "
                + "WHERE run_id = ?::uuid";
        try (Connection conn = openConnection();
             PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, status);
            if (parserMessageCount != null) st.setLong(2, parserMessageCount); else st.setNull(2, Types.BIGINT);
            if (validatorStatus != null) st.setString(3, validatorStatus); else st.setNull(3, Types.VARCHAR);
            st.setString(4, notesJson);
            st.setString(5, runId);
            st.executeUpdate();
            LOG.info("pipeline run completed  run_id={} status={}", runId, status);
        } catch (Exception e) {
            throw new RuntimeException("pipeline_runs update failed", e);
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
