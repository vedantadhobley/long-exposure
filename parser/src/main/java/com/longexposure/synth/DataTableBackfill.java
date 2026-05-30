package com.longexposure.synth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds {@code data_table} for existing rows of {@code daily_synthesis}
 * and {@code weekly_aggregate}. No LLM calls — the column is pure SQL
 * computed from {@code selected_events} / {@code narratives} on the same
 * Postgres connection.
 *
 * <p>Use after deploying the data-table builders against an already-
 * populated dataset, OR after changing builder logic in a way that
 * affects field shape (the existing prose stays put; only data_table
 * is rewritten).
 *
 * <p>Triggered by {@code IEX_BACKFILL_DATA_TABLES=all} (everything) or
 * {@code IEX_BACKFILL_DATA_TABLES=<YYYY-MM-DD>} (one day + the week it
 * belongs to) in {@link com.longexposure.Main}.
 */
public final class DataTableBackfill {

    private DataTableBackfill() {}

    public static void run(final String arg) throws Exception {
        System.out.println("== data_table backfill ==");
        System.out.println("scope: " + arg);
        System.out.println();

        ObjectMapper json = new ObjectMapper();
        int dailyProcessed = 0, weeklyProcessed = 0;
        int dailyErrors = 0, weeklyErrors = 0;

        try (Connection conn = openConnection()) {
            List<LocalDate> dailyDates = new ArrayList<>();
            List<LocalDate> weekStarts = new ArrayList<>();

            if ("all".equalsIgnoreCase(arg)) {
                try (PreparedStatement st = conn.prepareStatement(
                        "SELECT trading_date FROM daily_synthesis ORDER BY trading_date")) {
                    try (ResultSet rs = st.executeQuery()) {
                        while (rs.next()) dailyDates.add(rs.getObject(1, LocalDate.class));
                    }
                }
                try (PreparedStatement st = conn.prepareStatement(
                        "SELECT week_start FROM weekly_aggregate ORDER BY week_start")) {
                    try (ResultSet rs = st.executeQuery()) {
                        while (rs.next()) weekStarts.add(rs.getObject(1, LocalDate.class));
                    }
                }
            } else {
                LocalDate d = LocalDate.parse(arg);
                dailyDates.add(d);
                // Monday of d's ISO week.
                LocalDate weekStart = d.minusDays(d.getDayOfWeek().getValue() - 1L);
                weekStarts.add(weekStart);
            }

            for (LocalDate d : dailyDates) {
                try {
                    ObjectNode dataTable = DailyDataTableBuilder.build(conn, d, json);
                    updateDaily(conn, d, dataTable);
                    dailyProcessed++;
                    System.out.printf("  daily   %s — %d headline, %d per-scorer groups%n",
                            d,
                            dataTable.has("headline") ? dataTable.get("headline").size() : 0,
                            dataTable.has("per_scorer_top") ? dataTable.get("per_scorer_top").size() : 0);
                } catch (Exception e) {
                    dailyErrors++;
                    System.out.printf("  daily   %s — ERROR: %s%n", d, e.getMessage());
                }
            }

            for (LocalDate ws : weekStarts) {
                LocalDate we = ws.plusDays(4);  // Mon..Fri inclusive
                try {
                    ObjectNode dataTable = WeeklyDataTableBuilder.build(conn, ws, we, json);
                    updateWeekly(conn, ws, dataTable);
                    weeklyProcessed++;
                    System.out.printf("  weekly  %s..%s — %d exec bullets, %d headline%n",
                            ws, we,
                            dataTable.has("executive_summary") ? dataTable.get("executive_summary").size() : 0,
                            dataTable.has("headline_events") ? dataTable.get("headline_events").size() : 0);
                } catch (Exception e) {
                    weeklyErrors++;
                    System.out.printf("  weekly  %s — ERROR: %s%n", ws, e.getMessage());
                }
            }
        }

        System.out.println();
        System.out.println("== summary ==");
        System.out.printf("daily:   %d processed, %d errors%n", dailyProcessed, dailyErrors);
        System.out.printf("weekly:  %d processed, %d errors%n", weeklyProcessed, weeklyErrors);
    }

    private static void updateDaily(final Connection conn, final LocalDate d, final ObjectNode dataTable) throws Exception {
        String sql = "UPDATE daily_synthesis SET data_table = ?::jsonb WHERE trading_date = ?";
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, dataTable.toString());
            st.setObject(2, d);
            st.executeUpdate();
        }
    }

    private static void updateWeekly(final Connection conn, final LocalDate ws, final ObjectNode dataTable) throws Exception {
        String sql = "UPDATE weekly_aggregate SET data_table = ?::jsonb WHERE week_start = ?";
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, dataTable.toString());
            st.setObject(2, ws);
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
