package com.longexposure.temporal.activities;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.longexposure.analytics.Analytics;
import com.longexposure.scoring.BreakdownFmt;
import com.longexposure.storage.SchemaManager;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class EnrichAnalyticsActivityImpl implements EnrichAnalyticsActivity {

    private static final Logger LOG = LoggerFactory.getLogger(EnrichAnalyticsActivityImpl.class);

    /** Wire side codes (per the DPLS AddOrder spec): '8' = buy/bid, '5' = sell/ask. */
    private static final String SIDE_BID = "8";
    private static final String SIDE_ASK = "5";

    /** Below this dominant-side share, the withdrawal reads as genuinely two-sided. */
    private static final double TWO_SIDED_CUTOFF = 0.60;

    @Override
    public long enrichAnalytics(final LocalDate tradingDate) {
        ActivityExecutionContext actx = Activity.getExecutionContext();
        LOG.info("enrich-analytics start  date={}", tradingDate);
        long t0 = System.nanoTime();

        long enriched = 0;
        try (Connection conn = openConnection()) {
            SchemaManager.apply(conn);
            conn.setAutoCommit(false);

            List<Selected> events = loadSelected(conn, tradingDate);
            LOG.info("enrich-analytics  date={} selected_events={}", tradingDate, events.size());

            ObjectMapper json = new ObjectMapper();
            int processed = 0;
            for (Selected e : events) {
                ObjectNode add = json.createObjectNode();
                // Dispatch per scorer type. Each branch adds 0+ derived fields;
                // an empty `add` means "nothing to enrich" and is skipped.
                if ("liquidity_withdrawal".equals(e.scorerId)) {
                    addTwoSidedness(conn, e, add);
                }
                // (book-state tier — depth-from-touch / halt spread /
                //  %-of-book + recovery / effective spread — slots in here.)

                if (!add.isEmpty()) {
                    mergeBreakdown(conn, e.selectedId, add.toString());
                    enriched++;
                }
                if (++processed % 25 == 0) {
                    conn.commit();
                    actx.heartbeat("enriched:" + processed);
                }
            }
            conn.commit();
        } catch (Exception ex) {
            throw new RuntimeException("enrichAnalytics failed for date=" + tradingDate, ex);
        }

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        LOG.info("enrich-analytics done  date={} enriched={} elapsed_ms={}",
                tradingDate, enriched, elapsedMs);
        return enriched;
    }

    /**
     * Side-split / two-sidedness of a liquidity-withdrawal burst. Detection
     * stays on {@code orders_delete} (which carries no side) in the scorer;
     * here we recover side for the ~30 selected withdrawals by counting the
     * symbol's deleted orders in the event window from {@code order_lifecycle}
     * (which pairs each delete back to its add, and so carries side).
     *
     * <p>The order_lifecycle count can be marginally below the breakdown's
     * {@code deletes} (it omits cancels of orders added on a prior day), so we
     * publish a self-consistent set: the side counts, their sum
     * ({@code side_classified_cancels} — the denominator the ratio is over),
     * the ratio, and a class. A two-sided pull reads as de-risking ahead of
     * vol/news; a one-sided pull as directional.
     */
    private void addTwoSidedness(final Connection conn, final Selected e, final ObjectNode add) throws Exception {
        long bid = 0, ask = 0;
        String sql = """
                SELECT side, count(*) AS n
                FROM order_lifecycle
                WHERE trading_date = ?
                  AND symbol = ?
                  AND terminal_state = 'deleted'
                  AND delete_ts >= ?
                  AND delete_ts <= ?
                GROUP BY side
                """;
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, e.tradingDate);
            st.setString(2, e.symbol);
            st.setTimestamp(3, Timestamp.from(e.ts));
            st.setTimestamp(4, Timestamp.from(e.tsEnd));
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    String side = rs.getString("side");
                    long n = rs.getLong("n");
                    if (SIDE_BID.equals(side)) bid = n;
                    else if (SIDE_ASK.equals(side)) ask = n;
                }
            }
        }
        long classified = bid + ask;
        if (classified == 0) return;   // all cancels were of prior-day adds; leave untouched

        Analytics.OneSidedness os = Analytics.oneSidedness(bid, ask);
        String sideClass = os.ratio() < TWO_SIDED_CUTOFF ? "two_sided"
                : "buy".equals(os.dominant())  ? "bid_side"
                : "sell".equals(os.dominant()) ? "ask_side" : "two_sided";

        add.put("side_classified_cancels",   BreakdownFmt.formatCount(classified));
        add.put("bid_side_cancels",          BreakdownFmt.formatCount(bid));
        add.put("ask_side_cancels",          BreakdownFmt.formatCount(ask));
        add.put("withdrawal_sidedness_ratio", BreakdownFmt.round(os.ratio(), 2));
        add.put("withdrawal_side_class",      sideClass);
    }

    /** Shallow-merge the derived fields into the event's breakdown (idempotent — overwrites own keys). */
    private void mergeBreakdown(final Connection conn, final long selectedId, final String addJson) throws Exception {
        try (PreparedStatement st = conn.prepareStatement(
                "UPDATE selected_events SET breakdown = breakdown || ?::jsonb WHERE selected_id = ?")) {
            st.setString(1, addJson);
            st.setLong(2, selectedId);
            st.executeUpdate();
        }
    }

    private List<Selected> loadSelected(final Connection conn, final LocalDate date) throws Exception {
        String sql = """
                SELECT selected_id, scorer_id, symbol, ts, ts_end
                FROM selected_events
                WHERE trading_date = ?
                ORDER BY selected_id
                """;
        List<Selected> out = new ArrayList<>(256);
        try (PreparedStatement st = conn.prepareStatement(sql)) {
            st.setObject(1, date);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    Selected s = new Selected();
                    s.selectedId  = rs.getLong("selected_id");
                    s.scorerId    = rs.getString("scorer_id");
                    s.symbol      = rs.getString("symbol");
                    s.tradingDate = date;
                    s.ts          = rs.getTimestamp("ts").toInstant();
                    Timestamp end = rs.getTimestamp("ts_end");
                    s.tsEnd       = (end != null) ? end.toInstant() : s.ts;   // instantaneous events
                    out.add(s);
                }
            }
        }
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

    private static final class Selected {
        long      selectedId;
        String    scorerId;
        String    symbol;
        LocalDate tradingDate;
        Instant   ts;
        Instant   tsEnd;
    }
}
