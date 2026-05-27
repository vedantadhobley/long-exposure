package com.longexposure.scoring.scorers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.longexposure.scoring.EventScorer;
import com.longexposure.scoring.BreakdownFmt;
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
 * Sweep detector — finds clusters of {@code orders_executed} rows on the
 * same symbol within a tight time window that span multiple distinct
 * price levels. Reads as: "an aggressive incoming order walked through
 * the order book and took liquidity from N levels in M milliseconds."
 *
 * <p>Detection:
 * <ul>
 *   <li>Per symbol, partition executions into clusters where consecutive
 *       events are within {@link #CLUSTER_GAP_NANOS} of each other (10 ms)
 *   <li>Keep only clusters that span ≥ {@link #MIN_DISTINCT_LEVELS}
 *       distinct prices
 * </ul>
 *
 * <p>Score = {@code log10(notional_dollars) × distinct_levels}. A $5 M
 * sweep across 4 levels scores around {@code 6.7 × 4 = 26.8}; a $100 M
 * sweep across 10 levels scores {@code 8 × 10 = 80}. Cross-symbol
 * ranking within this scorer is meaningful; cross-scorer ranking is
 * out of scope (the selector handles top-N per scorer).
 *
 * <p>Breakdown JSON shape:
 * <pre>
 *   {
 *     "executions":         12,
 *     "distinct_levels":    4,
 *     "total_shares":       45_000,
 *     "notional_dollars":   9_122_300.00,
 *     "min_price_dollars":  202.10,
 *     "max_price_dollars":  202.31,
 *     "duration_nanos":     8_421_000,
 *     "start_iso":          "2026-05-08T14:23:14.847291Z",
 *     "end_iso":            "2026-05-08T14:23:14.855712Z"
 *   }
 * </pre>
 *
 * <p>Source refs: each execution row pointer in the cluster, by
 * {@code (symbol, ts_nanos, order_id, trade_id)}. Capped at
 * {@link #MAX_SOURCE_REFS} entries to keep breakdown size bounded for
 * very large sweeps (>50 executions).
 */
public final class SweepScorer implements EventScorer {

    private static final Logger LOG = LoggerFactory.getLogger(SweepScorer.class);

    /** Max nanos between consecutive executions to still count as the same cluster. */
    private static final long CLUSTER_GAP_NANOS = 10_000_000L;   // 10 ms

    /** Minimum distinct prices in a cluster for it to be a sweep. */
    private static final int MIN_DISTINCT_LEVELS = 3;

    /** Bounded buffer to defend against runaway clusters on busy names. */
    private static final int MAX_CLUSTER_SIZE = 10_000;

    /** Cap on source_refs array length so breakdown stays bounded. */
    private static final int MAX_SOURCE_REFS = 32;

    @Override
    public String id() { return "sweep"; }

    @Override
    public void score(final ScoringContext ctx, final Consumer<ScoredEvent> emit) {
        // Pull every execution for the day, ordered by (symbol, ts_nanos).
        // Cluster logic runs in-app — easier to reason about than a
        // gaps-and-islands SQL query and avoids window functions over a
        // multi-million-row table.
        String sql = """
                SELECT ts, ts_nanos, symbol, order_id, size, price_raw, trade_id
                FROM orders_executed
                WHERE feed_source = 'DPLS'
                  AND ts >= ? AND ts < ?
                ORDER BY symbol, ts_nanos
                """;

        Timestamp from = Timestamp.from(ctx.tradingDate().atStartOfDay().toInstant(ZoneOffset.UTC));
        Timestamp to   = Timestamp.from(ctx.tradingDate().plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        long[] emitted = {0};
        Consumer<ScoredEvent> counting = se -> { emit.accept(se); emitted[0]++; };
        try (PreparedStatement st = ctx.conn().prepareStatement(sql)) {
            st.setFetchSize(50_000);
            st.setTimestamp(1, from);
            st.setTimestamp(2, to);
            try (ResultSet rs = st.executeQuery()) {
                ClusterBuilder cb = new ClusterBuilder(ctx);
                long rowsRead = 0;
                while (rs.next()) {
                    cb.consume(rs, counting);
                    if (++rowsRead % 100_000 == 0) ctx.heartbeat().send("sweep:" + rowsRead);
                }
                cb.flush(counting);
            }
        } catch (Exception e) {
            throw new RuntimeException("SweepScorer query failed for date=" + ctx.tradingDate(), e);
        }

        LOG.info("SweepScorer  date={} sweeps_emitted={}", ctx.tradingDate(), emitted[0]);
    }

    /**
     * Streams through executions ordered by (symbol, ts_nanos), buffering
     * the current cluster. When the symbol changes or the time gap to the
     * next event exceeds {@link #CLUSTER_GAP_NANOS}, the current cluster
     * is flushed: if it qualifies as a sweep, a ScoredEvent is appended
     * to {@code out}.
     */
    private static final class ClusterBuilder {
        private final ScoringContext ctx;
        private final List<Execution> current = new ArrayList<>(16);

        ClusterBuilder(final ScoringContext ctx) { this.ctx = ctx; }

        void consume(final ResultSet rs, final Consumer<ScoredEvent> emit) throws Exception {
            Execution e = new Execution(
                    rs.getTimestamp("ts").toInstant(),
                    rs.getLong("ts_nanos"),
                    rs.getString("symbol"),
                    rs.getLong("order_id"),
                    rs.getInt("size"),
                    rs.getLong("price_raw"),
                    rs.getLong("trade_id"));

            if (!current.isEmpty()) {
                Execution last = current.get(current.size() - 1);
                boolean sameSymbol = last.symbol.equals(e.symbol);
                boolean withinGap  = (e.tsNanos - last.tsNanos) <= CLUSTER_GAP_NANOS;
                if (!sameSymbol || !withinGap) {
                    flush(emit);
                } else if (current.size() >= MAX_CLUSTER_SIZE) {
                    flush(emit);
                }
            }
            current.add(e);
        }

        void flush(final Consumer<ScoredEvent> emit) {
            if (current.isEmpty()) return;

            // Count distinct prices first — fast reject for the common case
            long distinctLevels = current.stream().mapToLong(x -> x.priceRaw).distinct().count();
            if (distinctLevels >= MIN_DISTINCT_LEVELS) {
                emit.accept(buildEvent(ctx, current, distinctLevels));
            }
            current.clear();
        }
    }

    private static ScoredEvent buildEvent(final ScoringContext ctx,
                                          final List<Execution> cluster,
                                          final long distinctLevels) {
        Execution first = cluster.get(0);
        Execution last  = cluster.get(cluster.size() - 1);

        long totalShares = 0;
        long minPriceRaw = Long.MAX_VALUE;
        long maxPriceRaw = Long.MIN_VALUE;
        double notional = 0.0;
        for (Execution e : cluster) {
            totalShares += e.size;
            if (e.priceRaw < minPriceRaw) minPriceRaw = e.priceRaw;
            if (e.priceRaw > maxPriceRaw) maxPriceRaw = e.priceRaw;
            notional += e.size * (e.priceRaw / 10_000.0);
        }

        long durationNanos = last.tsNanos - first.tsNanos;
        double durationMs = durationNanos / 1_000_000.0;
        double minPriceDollars = minPriceRaw / 10_000.0;
        double maxPriceDollars = maxPriceRaw / 10_000.0;
        double midPriceDollars = (minPriceDollars + maxPriceDollars) / 2.0;
        double priceRangeDollars = maxPriceDollars - minPriceDollars;

        ObjectMapper json = ctx.json();
        ObjectNode breakdown = json.createObjectNode();
        breakdown.put("executions",        BreakdownFmt.formatCount(cluster.size()));
        breakdown.put("distinct_levels",   BreakdownFmt.formatCount(distinctLevels));
        breakdown.put("total_shares",      BreakdownFmt.formatCount(totalShares));
        breakdown.put("notional_dollars",  BreakdownFmt.formatDollars(notional));
        breakdown.put("min_price_dollars", minPriceDollars);
        breakdown.put("max_price_dollars", maxPriceDollars);
        breakdown.put("duration",          BreakdownFmt.durationNanos(durationNanos));
        breakdown.put("start_et",          BreakdownFmt.toEtTime(first.ts));
        breakdown.put("end_et",            BreakdownFmt.toEtTime(last.ts));

        // ─── derived fields (DETECT enrichment, 2026-05-22) ──────────────
        // Pre-compute every quantity the LLM might want to mention so it
        // never has to do arithmetic at inference time.
        breakdown.put("duration_ms",          BreakdownFmt.round(durationMs, 2));
        breakdown.put("notional_per_level",   BreakdownFmt.formatDollars(notional / distinctLevels));
        breakdown.put("shares_per_level",     totalShares / distinctLevels);
        breakdown.put("price_range_dollars",  BreakdownFmt.round(priceRangeDollars, 4));
        if (midPriceDollars > 0) {
            breakdown.put("price_range_basis_points",
                          BreakdownFmt.round(priceRangeDollars / midPriceDollars * 10_000.0, 1));
        }
        if (durationMs > 0) {
            breakdown.put("executions_per_ms", BreakdownFmt.round(cluster.size() / durationMs, 2));
        }
        // Slippage: how far the price walked from the first fill to the last —
        // the cost the aggressor paid chewing through the offer/bid ladder.
        double slippageBps = com.longexposure.analytics.Analytics.slippageBps(first.priceRaw, last.priceRaw);
        breakdown.put("slippage_bps",          BreakdownFmt.round(Math.abs(slippageBps), 1));
        breakdown.put("slippage_direction",    slippageBps > 0 ? "up" : slippageBps < 0 ? "down" : "flat");
        breakdown.put("event_session_phase",  BreakdownFmt.sessionPhase(first.ts));
        breakdown.put("event_phase_label",    BreakdownFmt.sessionPhaseLabel(first.ts));

        ArrayNode sourceRefs = json.createArrayNode();
        int refsToEmit = Math.min(cluster.size(), MAX_SOURCE_REFS);
        for (int i = 0; i < refsToEmit; i++) {
            Execution e = cluster.get(i);
            ObjectNode ref = json.createObjectNode();
            ref.put("table",    "orders_executed");
            ref.put("symbol",   e.symbol);
            ref.put("ts_nanos", e.tsNanos);
            ref.put("order_id", e.orderId);
            ref.put("trade_id", e.tradeId);
            sourceRefs.add(ref);
        }
        if (cluster.size() > MAX_SOURCE_REFS) {
            ObjectNode trunc = json.createObjectNode();
            trunc.put("truncated_count", cluster.size() - MAX_SOURCE_REFS);
            sourceRefs.add(trunc);
        }

        com.longexposure.scoring.SymbolFields.apply(breakdown, ctx, first.symbol);

        double score = Math.log10(Math.max(notional, 1.0)) * distinctLevels;

        return new ScoredEvent(
                ctx.tradingDate(),
                first.symbol,
                first.ts,
                last.ts,
                "sweep",
                score,
                breakdown,
                sourceRefs);
    }

    private record Execution(
            Instant ts,
            long    tsNanos,
            String  symbol,
            long    orderId,
            int     size,
            long    priceRaw,
            long    tradeId) {}
}
