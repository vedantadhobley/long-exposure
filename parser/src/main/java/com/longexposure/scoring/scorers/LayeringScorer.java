package com.longexposure.scoring.scorers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.longexposure.scoring.EventScorer;
import com.longexposure.scoring.Humanize;
import com.longexposure.scoring.ScoredEvent;
import com.longexposure.scoring.ScoringContext;
import com.longexposure.scoring.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Layering detector. Looks for clusters of orders that were posted and
 * cancelled within a short lifetime AND span multiple price levels on
 * the same symbol/side. Distinguished from
 * {@link PostCancelClusterScorer}: layering specifically requires the
 * multi-level shape (artificial depth across price levels), not just a
 * burst of short-lived orders at one level.
 *
 * <p>Reuses the same SQL (short-lived add ⨝ delete pairs on
 * {@code order_id}) and adds a distinct-prices filter to the cluster
 * acceptance step. Layering ⊆ PostCancelCluster geometrically; both
 * scorers can match the same underlying event with different framings.
 *
 * <p>Score = log10(total_cluster_shares) × distinct_levels.
 *
 * <p>Breakdown JSON shape:
 * <pre>
 *   {
 *     "orders":             14,
 *     "distinct_levels":    5,
 *     "total_shares":       16_300,
 *     "median_lifetime_ms": 8.2,
 *     "side":               "5",       // '8' buy, '5' sell
 *     "min_price_dollars":  198.84,
 *     "max_price_dollars":  199.08,
 *     "duration_ms":        212.0,
 *     "start_iso":          "...",
 *     "end_iso":            "..."
 *   }
 * </pre>
 */
public final class LayeringScorer implements EventScorer {

    private static final Logger LOG = LoggerFactory.getLogger(LayeringScorer.class);

    private static final long MAX_LIFETIME_NANOS = 50_000_000L;       // 50 ms
    /** Tightened from 500 ms — same reason as PostCancelClusterScorer. */
    private static final long CLUSTER_GAP_NANOS  = 50_000_000L;       // 50 ms
    /** Raised from 5 — alongside tighter gap. */
    private static final int  MIN_ORDERS          = 20;
    /** Raised from 3 — layering should span more than just adjacent levels. */
    private static final int  MIN_DISTINCT_LEVELS = 5;
    private static final int  MAX_CLUSTER_SIZE    = 10_000;     // bounded buffer
    private static final int  MAX_SOURCE_REFS     = 32;

    @Override
    public String id() { return "layering"; }

    @Override
    public void score(final ScoringContext ctx, final Consumer<ScoredEvent> emit) {
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
        try (PreparedStatement st = ctx.conn().prepareStatement(sql)) {
            st.setFetchSize(50_000);
            st.setTimestamp(1, from);
            st.setTimestamp(2, to);
            st.setTimestamp(3, from);
            st.setTimestamp(4, to);
            st.setLong(5, MAX_LIFETIME_NANOS);
            try (ResultSet rs = st.executeQuery()) {
                ClusterBuilder cb = new ClusterBuilder(ctx);
                long rowsRead = 0;
                while (rs.next()) {
                    cb.consume(rs, counting);
                    if (++rowsRead % 100_000 == 0) ctx.heartbeat().send("layering:" + rowsRead);
                }
                cb.flush(counting);
            }
        } catch (Exception e) {
            throw new RuntimeException("LayeringScorer query failed for date=" + ctx.tradingDate(), e);
        }

        LOG.info("LayeringScorer  date={} layered_clusters={}", ctx.tradingDate(), emitted[0]);
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
                    flush(emit);
                }
            }
            current.add(o);
        }

        void flush(final Consumer<ScoredEvent> emit) {
            if (current.size() < MIN_ORDERS) {
                current.clear();
                return;
            }
            Set<Long> levels = new HashSet<>();
            for (ShortLivedOrder o : current) levels.add(o.priceRaw);
            if (levels.size() >= MIN_DISTINCT_LEVELS) {
                emit.accept(buildEvent(ctx, current, levels.size()));
            }
            current.clear();
        }
    }

    private static ScoredEvent buildEvent(final ScoringContext ctx, final List<ShortLivedOrder> cluster, final int distinctLevels) {
        ShortLivedOrder first = cluster.get(0);
        ShortLivedOrder last  = cluster.get(cluster.size() - 1);

        long totalShares = 0;
        long minPriceRaw = Long.MAX_VALUE;
        long maxPriceRaw = Long.MIN_VALUE;
        long[] lifetimes = new long[cluster.size()];
        for (int i = 0; i < cluster.size(); i++) {
            ShortLivedOrder o = cluster.get(i);
            totalShares += o.size;
            if (o.priceRaw < minPriceRaw) minPriceRaw = o.priceRaw;
            if (o.priceRaw > maxPriceRaw) maxPriceRaw = o.priceRaw;
            lifetimes[i] = o.lifetimeNanos;
        }
        java.util.Arrays.sort(lifetimes);
        double medianLifetimeMs = lifetimes[lifetimes.length / 2] / 1_000_000.0;

        ObjectMapper json = ctx.json();
        ObjectNode breakdown = json.createObjectNode();
        breakdown.put("orders",                cluster.size());
        breakdown.put("distinct_levels",       distinctLevels);
        breakdown.put("total_shares",          totalShares);
        breakdown.put("median_order_lifetime", Humanize.durationMs(medianLifetimeMs));
        breakdown.put("side",                  Side.label(first.side));
        breakdown.put("min_price_dollars",     minPriceRaw / 10_000.0);
        breakdown.put("max_price_dollars",     maxPriceRaw / 10_000.0);
        breakdown.put("duration",              Humanize.durationNanos(last.addNanos - first.addNanos));
        breakdown.put("start_et",              Humanize.toEtTime(first.addTs));
        breakdown.put("end_et",                Humanize.toEtTime(last.addTs));

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

        double score = Math.log10(Math.max(totalShares, 1)) * distinctLevels;

        return new ScoredEvent(
                ctx.tradingDate(),
                first.symbol,
                first.addTs,
                last.addTs,
                "layering",
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
