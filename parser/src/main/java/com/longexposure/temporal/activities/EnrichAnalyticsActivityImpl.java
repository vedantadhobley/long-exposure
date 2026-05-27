package com.longexposure.temporal.activities;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.longexposure.analytics.Analytics;
import com.longexposure.analytics.BookSnapshotEngine;
import com.longexposure.scoring.BreakdownFmt;
import com.longexposure.storage.SchemaManager;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

public final class EnrichAnalyticsActivityImpl implements EnrichAnalyticsActivity {

    private static final Logger LOG = LoggerFactory.getLogger(EnrichAnalyticsActivityImpl.class);

    /** Wire side codes (per the DPLS AddOrder spec): '8' = buy/bid, '5' = sell/ask. */
    private static final String SIDE_BID = "8";
    private static final String SIDE_ASK = "5";

    /** Below this dominant-side share, the withdrawal reads as genuinely two-sided. */
    private static final double TWO_SIDED_CUTOFF = 0.60;

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Where the day's DPLS pcap lives during ScoreWorkflow (before CleanupFiles). */
    private static final String RAW_DIR = System.getenv().getOrDefault("IEX_RAW_DIR", "/storage/raw");
    private static final DateTimeFormatter PCAP_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** Recovery snapshot offset after a withdrawal ends — did displayed depth come back? */
    private static final long RECOVERY_NS = 5_000_000_000L;  // 5 s

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

            // Phase 2 — book-state tier: one decode-only DPLS pcap pass snapshots
            // each book-needing event's book at its timestamp(s) and derives the
            // stats that reference the book's STATE (halt spread, layering
            // depth-from-touch, liquidity_withdrawal %-of-book + recovery).
            enriched += addBookReplayStats(conn, tradingDate, events, actx);
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

    /**
     * Book-state tier: one decode-only DPLS pcap pass (the validated
     * {@link BookSnapshotEngine}) snapshots each book-needing event's book at
     * its timestamp(s), then derives the stats whose definition references the
     * book's STATE at the event — halt pre-event spread, layering
     * depth-from-touch, liquidity_withdrawal %-of-book-removed + recovery.
     * Skips cleanly (returns 0) if the day's pcap isn't on disk; the fields are
     * omitted rather than guessed.
     *
     * @return number of events that gained a book-state field.
     */
    private long addBookReplayStats(final Connection conn, final LocalDate date,
                                    final List<Selected> events,
                                    final ActivityExecutionContext actx) throws Exception {
        Path pcap = Path.of(RAW_DIR, date.format(PCAP_DATE) + "_IEXTP1_DPLS1.0.pcap.gz");
        if (!Files.exists(pcap)) {
            LOG.warn("book-replay skipped — pcap not on disk: {} (book-state stats omitted)", pcap);
            return 0;
        }

        List<BookSnapshotEngine.Request> reqs = new ArrayList<>();
        for (Selected e : events) {
            long ts    = epochNanos(e.ts);
            long tsEnd = epochNanos(e.tsEnd);
            switch (e.scorerId) {
                case "halt", "layering" ->
                        reqs.add(new BookSnapshotEngine.Request(e.selectedId, e.symbol, ts, "at"));
                case "liquidity_withdrawal" -> {
                    reqs.add(new BookSnapshotEngine.Request(e.selectedId, e.symbol, ts, "before"));
                    reqs.add(new BookSnapshotEngine.Request(e.selectedId, e.symbol, tsEnd, "after"));
                    reqs.add(new BookSnapshotEngine.Request(e.selectedId, e.symbol, tsEnd + RECOVERY_NS, "recovery"));
                }
                default -> { }
            }
        }
        if (reqs.isEmpty()) return 0;

        LOG.info("book-replay  date={} pcap={} requests={}", date, pcap.getFileName(), reqs.size());
        List<BookSnapshotEngine.Snapshot> snaps = BookSnapshotEngine.run(pcap, reqs, actx::heartbeat);

        Map<Long, Map<String, BookSnapshotEngine.Snapshot>> byEvent = new HashMap<>();
        for (BookSnapshotEngine.Snapshot s : snaps) {
            byEvent.computeIfAbsent(s.selectedId(), k -> new HashMap<>()).put(s.role(), s);
        }

        long enriched = 0;
        for (Selected e : events) {
            Map<String, BookSnapshotEngine.Snapshot> s = byEvent.get(e.selectedId);
            if (s == null) continue;
            ObjectNode add = JSON.createObjectNode();
            switch (e.scorerId) {
                case "halt"                 -> addHaltSpread(add, s.get("at"));
                case "layering"             -> addDepthFromTouch(add, s.get("at"), e.breakdown);
                case "liquidity_withdrawal" -> addPctOfBook(add, s.get("before"), s.get("after"), s.get("recovery"));
                default -> { }
            }
            if (!add.isEmpty()) {
                mergeBreakdown(conn, e.selectedId, add.toString());
                enriched++;
            }
        }
        LOG.info("book-replay done  date={} events_enriched={}", date, enriched);
        return enriched;
    }

    /** Spread (abs + bps) at halt onset — "spreads were already N bps when trading halted". */
    private void addHaltSpread(final ObjectNode add, final BookSnapshotEngine.Snapshot s) {
        if (s == null || !s.captured() || s.bestBidPriceRaw().isEmpty() || s.bestAskPriceRaw().isEmpty()) return;
        long bidRaw = s.bestBidPriceRaw().getAsLong();
        long askRaw = s.bestAskPriceRaw().getAsLong();
        long spreadRaw = askRaw - bidRaw;
        if (spreadRaw <= 0) return;
        double mid = (bidRaw + askRaw) / 2.0 / 10_000.0;
        add.put("pre_halt_spread_dollars", BreakdownFmt.round(spreadRaw / 10_000.0, 4));
        if (mid > 0) add.put("pre_halt_spread_bps", BreakdownFmt.round(spreadRaw / 10_000.0 / mid * 10_000.0, 1));
    }

    /** Distance of the layered price band from the touch (BBO on the layered side), in bps. */
    private void addDepthFromTouch(final ObjectNode add, final BookSnapshotEngine.Snapshot s, final JsonNode breakdown) {
        if (s == null || !s.captured() || breakdown == null) return;
        String side = breakdown.path("side").asText("");
        OptionalLong touchOpt = "sell".equals(side) ? s.bestAskPriceRaw()
                              : "buy".equals(side)  ? s.bestBidPriceRaw() : OptionalLong.empty();
        if (touchOpt.isEmpty()) return;
        double touch = touchOpt.getAsLong() / 10_000.0;
        if (touch <= 0) return;
        JsonNode minN = breakdown.get("min_price_dollars");
        JsonNode maxN = breakdown.get("max_price_dollars");
        if (minN == null || maxN == null) return;
        double dMin = Math.abs(minN.asDouble() - touch) / touch * 10_000.0;
        double dMax = Math.abs(maxN.asDouble() - touch) / touch * 10_000.0;
        add.put("depth_from_touch_near_bps", BreakdownFmt.round(Math.min(dMin, dMax), 1));
        add.put("depth_from_touch_far_bps",  BreakdownFmt.round(Math.max(dMin, dMax), 1));
    }

    /** %-of-displayed-book removed across the withdrawal + how much had recovered after. */
    private void addPctOfBook(final ObjectNode add, final BookSnapshotEngine.Snapshot before,
                              final BookSnapshotEngine.Snapshot after, final BookSnapshotEngine.Snapshot recovery) {
        if (before == null || !before.captured()) return;
        long beforeTotal = before.totalBidSize() + before.totalAskSize();
        if (beforeTotal <= 0) return;
        if (after != null && after.captured()) {
            long afterTotal = after.totalBidSize() + after.totalAskSize();
            double removed = (beforeTotal - afterTotal) / (double) beforeTotal * 100.0;
            // Only emit when depth actually dropped. A negative value means the
            // displayed book GREW across the window (incoming orders outpaced the
            // cancels) — not a "removal"; omit rather than narrate "removed -18%".
            if (removed >= 0) add.put("pct_of_book_removed", BreakdownFmt.round(removed, 1));
        }
        if (recovery != null && recovery.captured()) {
            long recTotal = recovery.totalBidSize() + recovery.totalAskSize();
            add.put("depth_recovery_pct", BreakdownFmt.round(recTotal / (double) beforeTotal * 100.0, 0));
        }
    }

    private static long epochNanos(final Instant t) {
        return t.getEpochSecond() * 1_000_000_000L + t.getNano();
    }

    private List<Selected> loadSelected(final Connection conn, final LocalDate date) throws Exception {
        String sql = """
                SELECT selected_id, scorer_id, symbol, ts, ts_end, breakdown::text AS breakdown
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
                    s.breakdown   = JSON.readTree(rs.getString("breakdown"));
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
        JsonNode  breakdown;
    }
}
