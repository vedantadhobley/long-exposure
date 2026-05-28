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
 * Liquidity withdrawal detector. Finds bursts of {@code orders_delete}
 * events on the same symbol concentrated in time — interpretation:
 * "market makers pulling quotes ahead of volatility / news / halt."
 *
 * <p>Detection: stream deletes ordered by {@code (symbol, ts_nanos)},
 * cluster consecutive events that are within {@link #CLUSTER_GAP_NANOS}
 * of each other. Emit clusters with ≥ {@link #MIN_DELETES} cancellations.
 *
 * <p>Score = log10(deletes_in_cluster) × deletes_in_cluster. Bigger
 * burst, higher score; logarithmic so a 10× larger burst scores ~2×
 * higher rather than dominating.
 *
 * <p>Breakdown JSON shape:
 * <pre>
 *   {
 *     "deletes":            187,
 *     "duration_ms":        72.3,
 *     "rate_per_sec":       2587.0,
 *     "start_iso":          "...",
 *     "end_iso":            "..."
 *   }
 * </pre>
 *
 * <p>v1 simplification: doesn't filter to "top-of-book" deletes
 * specifically (the spec catalog suggests "top 5 levels"). Determining
 * level-rank at delete time requires book-state replay; the simpler
 * "raw burst of cancels on a symbol" is a reasonable approximation
 * that we can tighten in a later sprint.
 */
public final class LiquidityWithdrawalScorer implements EventScorer {

    private static final Logger LOG = LoggerFactory.getLogger(LiquidityWithdrawalScorer.class);

    /** Max nanos between consecutive deletes to stay in the same cluster. */
    private static final long CLUSTER_GAP_NANOS = 100_000_000L;    // 100 ms

    /** Minimum number of cancellations in a cluster to call it a withdrawal. */
    private static final int  MIN_DELETES = 50;

    /** Bounded buffer for very-high-rate withdrawal bursts. */
    private static final int  MAX_CLUSTER_SIZE = 10_000;

    private static final int MAX_SOURCE_REFS = 16;

    @Override
    public String id() { return "liquidity_withdrawal"; }

    @Override
    public void score(final ScoringContext ctx, final Consumer<ScoredEvent> emit) {
        String sql = """
                SELECT ts, ts_nanos, symbol, order_id
                FROM orders_delete
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
                    if (++rowsRead % 100_000 == 0) ctx.heartbeat().send("liquidity:" + rowsRead);
                }
                cb.flush(counting);
            }
        } catch (Exception e) {
            throw new RuntimeException("LiquidityWithdrawalScorer query failed for date=" + ctx.tradingDate(), e);
        }

        LOG.info("LiquidityWithdrawalScorer  date={} bursts_emitted={}", ctx.tradingDate(), emitted[0]);
    }

    private static final class ClusterBuilder {
        private final ScoringContext ctx;
        private final List<Cancel> current = new ArrayList<>(32);

        ClusterBuilder(final ScoringContext ctx) { this.ctx = ctx; }

        void consume(final ResultSet rs, final Consumer<ScoredEvent> emit) throws Exception {
            Cancel c = new Cancel(
                    rs.getTimestamp("ts").toInstant(),
                    rs.getLong("ts_nanos"),
                    rs.getString("symbol"),
                    rs.getLong("order_id"));

            if (!current.isEmpty()) {
                Cancel last = current.get(current.size() - 1);
                boolean sameSymbol = last.symbol.equals(c.symbol);
                boolean withinGap  = (c.tsNanos - last.tsNanos) <= CLUSTER_GAP_NANOS;
                if (!sameSymbol || !withinGap) {
                    flush(emit);
                } else if (current.size() >= MAX_CLUSTER_SIZE) {
                    flush(emit);
                }
            }
            current.add(c);
        }

        void flush(final Consumer<ScoredEvent> emit) {
            if (current.size() >= MIN_DELETES) {
                emit.accept(buildEvent(ctx, current));
            }
            current.clear();
        }
    }

    private static ScoredEvent buildEvent(final ScoringContext ctx, final List<Cancel> cluster) {
        Cancel first = cluster.get(0);
        Cancel last  = cluster.get(cluster.size() - 1);
        long durationNanos = last.tsNanos - first.tsNanos;
        double durationMs  = durationNanos / 1_000_000.0;
        double ratePerSec  = durationNanos > 0 ? cluster.size() / (durationNanos / 1_000_000_000.0) : 0.0;

        double durationSec = durationNanos / 1_000_000_000.0;

        ObjectMapper json = ctx.json();
        ObjectNode breakdown = json.createObjectNode();
        breakdown.put("deletes",      BreakdownFmt.formatCount(cluster.size()));
        breakdown.put("duration",     BreakdownFmt.durationMs(durationMs));
        breakdown.put("rate_per_sec", BreakdownFmt.round(ratePerSec, 2));
        breakdown.put("start_et",     BreakdownFmt.toEtTime(first.ts));
        breakdown.put("end_et",       BreakdownFmt.toEtTime(last.ts));

        // ─── derived fields (DETECT enrichment, 2026-05-22) ──────────────
        // LiquidityWithdrawalScorer doesn't track per-cancel price level
        // (orders_delete rows don't carry price — that's on the original
        // add). distinct_levels-derived fields are deferred until the
        // scorer is extended to join order_lifecycle for the level lookup.
        breakdown.put("duration_seconds",      BreakdownFmt.round(durationSec, 2));
        // Pluralization-safe phrasing for prose. Eliminates "1 seconds"
        // (observed 2026-05-28 IWM audit). Rounded to whole seconds since
        // sub-second resolution isn't meaningful in journalist prose.
        breakdown.put("duration_humanized",    BreakdownFmt.durationSecHumanized(Math.round(durationSec)));
        breakdown.put("burst_intensity_class",
                durationSec < 1.0   ? "sub_second" :
                durationSec < 10.0  ? "brief" :
                durationSec < 60.0  ? "sustained" : "extended");
        breakdown.put("event_session_phase",   BreakdownFmt.sessionPhase(first.ts));
        breakdown.put("event_phase_label",     BreakdownFmt.sessionPhaseLabel(first.ts));

        ArrayNode sourceRefs = json.createArrayNode();
        int refsToEmit = Math.min(cluster.size(), MAX_SOURCE_REFS);
        for (int i = 0; i < refsToEmit; i++) {
            Cancel c = cluster.get(i);
            ObjectNode ref = json.createObjectNode();
            ref.put("table",    "orders_delete");
            ref.put("symbol",   c.symbol);
            ref.put("ts_nanos", c.tsNanos);
            ref.put("order_id", c.orderId);
            sourceRefs.add(ref);
        }
        if (cluster.size() > MAX_SOURCE_REFS) {
            ObjectNode trunc = json.createObjectNode();
            trunc.put("truncated_count", cluster.size() - MAX_SOURCE_REFS);
            sourceRefs.add(trunc);
        }

        com.longexposure.scoring.SymbolFields.apply(breakdown, ctx, first.symbol);

        double score = Math.log10(cluster.size()) * cluster.size();

        return new ScoredEvent(
                ctx.tradingDate(),
                first.symbol,
                first.ts,
                last.ts,
                "liquidity_withdrawal",
                score,
                breakdown,
                sourceRefs);
    }

    private record Cancel(Instant ts, long tsNanos, String symbol, long orderId) {}
}
