package com.longexposure.scoring.scorers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.longexposure.scoring.EventScorer;
import com.longexposure.scoring.ScoredEvent;
import com.longexposure.scoring.ScoringContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Post-cancel cluster detector. Finds bursts of orders that were posted
 * and cancelled within a very short lifetime, clustered in time on the
 * same symbol and side. Reads as "many small orders posted and yanked
 * within milliseconds on AAPL bid — classic spoofing-shape signal."
 *
 * <p>Two-stage detection:
 * <ol>
 *   <li>SQL join {@code orders_add} ⨝ {@code orders_delete} on
 *       {@code order_id}, keep pairs with lifetime in
 *       (0, {@link #MAX_LIFETIME_NANOS}]. The join is bounded to the
 *       trading date on both sides.
 *   <li>In-app: stream the short-lived orders ordered by
 *       (symbol, side, add_nanos), cluster consecutive events within
 *       {@link #CLUSTER_GAP_NANOS}. Emit clusters with
 *       ≥ {@link #MIN_ORDERS_PER_CLUSTER} short-lived orders.
 * </ol>
 *
 * <p>Score = log10(total_cluster_size_shares) × ordersInCluster. Bigger
 * cluster, more cancelled shares, higher score.
 *
 * <p>Breakdown JSON shape:
 * <pre>
 *   {
 *     "orders":             12,
 *     "total_shares":       12_300,
 *     "median_lifetime_ms": 8.4,
 *     "side":               "8"      // '8' buy, '5' sell
 *     "duration_ms":        420.1,   // cluster span
 *     "start_iso":          "...",
 *     "end_iso":            "..."
 *   }
 * </pre>
 *
 * <p>Source refs: pointers to the first {@link #MAX_SOURCE_REFS} adds in
 * the cluster. Truncation marker added if cluster exceeds the cap.
 */
public final class PostCancelClusterScorer implements EventScorer {

    private static final Logger LOG = LoggerFactory.getLogger(PostCancelClusterScorer.class);

    /** Max lifetime (add → delete) for an order to count as "short-lived". */
    private static final long MAX_LIFETIME_NANOS = 50_000_000L;       // 50 ms

    /**
     * Max gap between consecutive short-lived orders to be in the same
     * cluster. Tightened from 500 ms → 50 ms after observing that the
     * looser gap produced 3.6 M clusters on a single day — effectively
     * "every continuous activity stream" rather than rapid bursts.
     */
    private static final long CLUSTER_GAP_NANOS = 50_000_000L;        // 50 ms

    /**
     * Minimum number of short-lived orders in a cluster to emit a scored
     * event. Raised from 5 → 20 alongside the tighter gap; 20+ orders
     * all rapid-cancelled within 50-ms-apart windows is a real burst
     * shape, not background noise.
     */
    private static final int MIN_ORDERS_PER_CLUSTER = 20;

    /**
     * Cap on cluster buffer size. When a cluster exceeds this, we emit it
     * as a scored event and reset — prevents unbounded growth on busy
     * symbols where consecutive short-lived orders never break the
     * 500-ms gap window.
     */
    private static final int MAX_CLUSTER_SIZE = 10_000;

    private static final int MAX_SOURCE_REFS = 32;

    @Override
    public String id() { return "post_cancel_cluster"; }

    @Override
    public void score(final ScoringContext ctx, final Consumer<ScoredEvent> emit) {
        // Join add ⨝ delete on order_id, filter to short-lived pairs.
        // The order_id index on both tables makes this a merge/hash join;
        // postgres picks the right plan based on cardinality.
        String sql = """
                SELECT a.symbol,
                       a.side,
                       a.order_id,
                       a.ts        AS add_ts,
                       a.ts_nanos  AS add_nanos,
                       a.size,
                       a.price_raw,
                       d.ts_nanos  AS delete_nanos
                FROM orders_add a
                JOIN orders_delete d ON a.order_id = d.order_id
                WHERE a.feed_source = 'DPLS'
                  AND d.feed_source = 'DPLS'
                  AND a.ts >= ? AND a.ts < ?
                  AND d.ts >= ? AND d.ts < ?
                  AND (d.ts_nanos - a.ts_nanos) > 0
                  AND (d.ts_nanos - a.ts_nanos) <= ?
                ORDER BY a.symbol, a.side, a.ts_nanos
                """;

        Timestamp from = Timestamp.from(ctx.tradingDate().atStartOfDay().toInstant(ZoneOffset.UTC));
        Timestamp to   = Timestamp.from(ctx.tradingDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        long[] emitted = {0};
        Consumer<ScoredEvent> counting = se -> { emit.accept(se); emitted[0]++; };
        long pairsExamined = 0;
        try (PreparedStatement st = ctx.conn().prepareStatement(sql)) {
            st.setFetchSize(50_000);
            st.setTimestamp(1, from);
            st.setTimestamp(2, to);
            st.setTimestamp(3, from);
            st.setTimestamp(4, to);
            st.setLong(5, MAX_LIFETIME_NANOS);
            try (ResultSet rs = st.executeQuery()) {
                ClusterBuilder cb = new ClusterBuilder(ctx);
                while (rs.next()) {
                    pairsExamined++;
                    cb.consume(rs, counting);
                    if (pairsExamined % 100_000 == 0) ctx.heartbeat().send("post_cancel:" + pairsExamined);
                }
                cb.flush(counting);
            }
        } catch (Exception e) {
            throw new RuntimeException("PostCancelClusterScorer query failed for date=" + ctx.tradingDate(), e);
        }

        LOG.info("PostCancelClusterScorer  date={} short_lived_pairs={} clusters_emitted={}",
                ctx.tradingDate(), pairsExamined, emitted[0]);
    }

    private static final class ClusterBuilder {
        private final ScoringContext ctx;
        private final List<ShortLivedOrder> current = new ArrayList<>(16);

        ClusterBuilder(final ScoringContext ctx) { this.ctx = ctx; }

        void consume(final ResultSet rs, final Consumer<ScoredEvent> emit) throws Exception {
            ShortLivedOrder o = new ShortLivedOrder(
                    rs.getString("symbol"),
                    rs.getString("side"),
                    rs.getLong("order_id"),
                    rs.getTimestamp("add_ts").toInstant(),
                    rs.getLong("add_nanos"),
                    rs.getInt("size"),
                    rs.getLong("price_raw"),
                    rs.getLong("delete_nanos") - rs.getLong("add_nanos"));

            if (!current.isEmpty()) {
                ShortLivedOrder last = current.get(current.size() - 1);
                boolean sameKey   = last.symbol.equals(o.symbol) && last.side.equals(o.side);
                boolean withinGap = (o.addNanos - last.addNanos) <= CLUSTER_GAP_NANOS;
                if (!sameKey || !withinGap) {
                    flush(emit);
                } else if (current.size() >= MAX_CLUSTER_SIZE) {
                    // Bounded buffer: emit-and-reset before the buffer
                    // grows without limit on continuous-activity symbols.
                    flush(emit);
                }
            }
            current.add(o);
        }

        void flush(final Consumer<ScoredEvent> emit) {
            if (current.size() >= MIN_ORDERS_PER_CLUSTER) {
                emit.accept(buildEvent(ctx, current));
            }
            current.clear();
        }
    }

    private static ScoredEvent buildEvent(final ScoringContext ctx, final List<ShortLivedOrder> cluster) {
        ShortLivedOrder first = cluster.get(0);
        ShortLivedOrder last  = cluster.get(cluster.size() - 1);

        long totalShares = 0;
        long[] lifetimes = new long[cluster.size()];
        for (int i = 0; i < cluster.size(); i++) {
            ShortLivedOrder o = cluster.get(i);
            totalShares  += o.size;
            lifetimes[i] = o.lifetimeNanos;
        }
        java.util.Arrays.sort(lifetimes);
        double medianLifetimeMs = lifetimes[lifetimes.length / 2] / 1_000_000.0;

        ObjectMapper json = ctx.json();
        ObjectNode breakdown = json.createObjectNode();
        breakdown.put("orders",             cluster.size());
        breakdown.put("total_shares",       totalShares);
        breakdown.put("median_lifetime_ms", medianLifetimeMs);
        breakdown.put("side",               first.side);
        breakdown.put("duration_ms",        (last.addNanos - first.addNanos) / 1_000_000.0);
        breakdown.put("start_iso",          first.addTs.toString());
        breakdown.put("end_iso",            last.addTs.toString());

        ArrayNode sourceRefs = json.createArrayNode();
        int refsToEmit = Math.min(cluster.size(), MAX_SOURCE_REFS);
        for (int i = 0; i < refsToEmit; i++) {
            ShortLivedOrder o = cluster.get(i);
            ObjectNode ref = json.createObjectNode();
            ref.put("table",    "orders_add");
            ref.put("symbol",   o.symbol);
            ref.put("ts_nanos", o.addNanos);
            ref.put("order_id", o.orderId);
            sourceRefs.add(ref);
        }
        if (cluster.size() > MAX_SOURCE_REFS) {
            ObjectNode trunc = json.createObjectNode();
            trunc.put("truncated_count", cluster.size() - MAX_SOURCE_REFS);
            sourceRefs.add(trunc);
        }

        double score = Math.log10(Math.max(totalShares, 1)) * cluster.size();

        return new ScoredEvent(
                ctx.tradingDate(),
                first.symbol,
                first.addTs,
                last.addTs,
                "post_cancel_cluster",
                score,
                breakdown,
                sourceRefs);
    }

    private record ShortLivedOrder(
            String  symbol,
            String  side,
            long    orderId,
            Instant addTs,
            long    addNanos,
            int     size,
            long    priceRaw,
            long    lifetimeNanos) {}
}
