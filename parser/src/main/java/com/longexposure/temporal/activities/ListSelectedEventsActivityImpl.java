package com.longexposure.temporal.activities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class ListSelectedEventsActivityImpl implements ListSelectedEventsActivity {

    private static final Logger LOG = LoggerFactory.getLogger(ListSelectedEventsActivityImpl.class);

    @Override
    public List<Long> list(final LocalDate tradingDate) {
        List<Long> out = new ArrayList<>(128);
        String sql = """
                SELECT selected_id
                FROM selected_events
                WHERE trading_date = ?
                ORDER BY scorer_id, narration_rank
                """;
        try (Connection conn = openConnection();
             PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, tradingDate);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) out.add(rs.getLong(1));
            }
        } catch (Exception e) {
            throw new RuntimeException("listSelectedEvents failed for date=" + tradingDate, e);
        }
        LOG.info("listed selected_events  date={} count={}", tradingDate, out.size());
        return out;
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
