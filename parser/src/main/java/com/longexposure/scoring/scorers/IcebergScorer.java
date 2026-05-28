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
 * Iceberg detector. Looks for sustained repeated fills of similar size at
 * the same {@code (symbol, price)} — the shape of a hidden displayed
 * order being worked. From the public feed we only see the executions
 * against the displayed tip; the reserve is invisible by design. So
 * "many ~equal-sized fills at one price over minutes" is the inferential
 * signal.
 *
 * <p>Detection:
 * <ol>
 *   <li>Stream {@code orders_executed} ordered by
 *       {@code (symbol, price_raw, ts_nanos)}.
 *   <li>Group consecutive runs by {@code (symbol, price_raw)}.
 *   <li>Within each run, require:
 *       <ul>
 *         <li>≥ {@link #MIN_FILLS} executions
 *         <li>Duration ≥ {@link #MIN_DURATION_NANOS}
 *         <li>Size coefficient of variation ≤ {@link #MAX_SIZE_CV} — fills
 *             are "similar size"
 *       </ul>
 * </ol>
 *
 * <p>Score = log10(total_filled_shares) × fillCount.
 *
 * <p>Breakdown JSON shape:
 * <pre>
 *   {
 *     "fills":              28,
 *     "total_shares":       28_000,
 *     "price_dollars":      247.83,
 *     "median_fill_size":   1_000,
 *     "size_cv":            0.06,
 *     "duration_seconds":   840,
 *     "start_iso":          "...",
 *     "end_iso":            "..."
 *   }
 * </pre>
 */
public final class IcebergScorer implements EventScorer {

    private static final Logger LOG = LoggerFactory.getLogger(IcebergScorer.class);

    /** Min consecutive fills at the same price to qualify. */
    private static final int MIN_FILLS = 8;

    /** Min sustained duration (start of first fill → start of last fill). */
    private static final long MIN_DURATION_NANOS = 30L * 1_000_000_000L; // 30 sec

    /** Max coefficient of variation of fill sizes (= stddev / mean). Smaller means more uniform. */
    private static final double MAX_SIZE_CV = 0.20;

    /** Reset the run when the gap between consecutive fills exceeds this. */
    private static final long RUN_GAP_NANOS = 600L * 1_000_000_000L;     // 10 min

    /** Bounded buffer to prevent unbounded growth on heavy traded names. */
    private static final int MAX_RUN_SIZE = 10_000;

    private static final int MAX_SOURCE_REFS = 32;

    @Override
    public String id() { return "iceberg"; }

    @Override
    public void score(final ScoringContext ctx, final Consumer<ScoredEvent> emit) {
        String sql = """
                SELECT ts, ts_nanos, symbol, price_raw, size, order_id, trade_id
                FROM orders_executed
                WHERE feed_source = 'DPLS'
                  AND ts >= ? AND ts < ?
                ORDER BY symbol, price_raw, ts_nanos
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
                RunBuilder rb = new RunBuilder(ctx);
                long rowsRead = 0;
                while (rs.next()) {
                    rb.consume(rs, counting);
                    if (++rowsRead % 100_000 == 0) ctx.heartbeat().send("iceberg:" + rowsRead);
                }
                rb.flush(counting);
            }
        } catch (Exception e) {
            throw new RuntimeException("IcebergScorer query failed for date=" + ctx.tradingDate(), e);
        }

        LOG.info("IcebergScorer  date={} icebergs_emitted={}", ctx.tradingDate(), emitted[0]);
    }

    private static final class RunBuilder {
        private final ScoringContext ctx;
        private final List<Fill> current = new ArrayList<>(16);

        RunBuilder(final ScoringContext ctx) { this.ctx = ctx; }

        void consume(final ResultSet rs, final Consumer<ScoredEvent> emit) throws Exception {
            Fill f = new Fill(
                    rs.getTimestamp("ts").toInstant(),
                    rs.getLong("ts_nanos"),
                    rs.getString("symbol"),
                    rs.getLong("price_raw"),
                    rs.getInt("size"),
                    rs.getLong("order_id"),
                    rs.getLong("trade_id"));

            if (!current.isEmpty()) {
                Fill last = current.get(current.size() - 1);
                boolean sameKey  = last.symbol.equals(f.symbol) && last.priceRaw == f.priceRaw;
                boolean inWindow = (f.tsNanos - last.tsNanos) <= RUN_GAP_NANOS;
                if (!sameKey || !inWindow) {
                    flush(emit);
                } else if (current.size() >= MAX_RUN_SIZE) {
                    flush(emit);
                }
            }
            current.add(f);
        }

        void flush(final Consumer<ScoredEvent> emit) {
            if (current.size() < MIN_FILLS) { current.clear(); return; }

            Fill first = current.get(0);
            Fill last  = current.get(current.size() - 1);
            long durationNanos = last.tsNanos - first.tsNanos;
            if (durationNanos < MIN_DURATION_NANOS) { current.clear(); return; }

            // Coefficient of variation = stddev / mean. Low CV means similar-size fills.
            double mean = current.stream().mapToInt(x -> x.size).average().orElse(0);
            if (mean <= 0) { current.clear(); return; }
            double variance = current.stream()
                    .mapToDouble(x -> (x.size - mean) * (x.size - mean))
                    .sum() / current.size();
            double cv = Math.sqrt(variance) / mean;
            if (cv > MAX_SIZE_CV) { current.clear(); return; }

            emit.accept(buildEvent(ctx, current, cv));
            current.clear();
        }
    }

    private static ScoredEvent buildEvent(final ScoringContext ctx, final List<Fill> run, final double cv) {
        Fill first = run.get(0);
        Fill last  = run.get(run.size() - 1);

        long totalShares = 0;
        int[] sizes = new int[run.size()];
        for (int i = 0; i < run.size(); i++) {
            totalShares += run.get(i).size;
            sizes[i] = run.get(i).size;
        }
        java.util.Arrays.sort(sizes);
        int medianSize = sizes[sizes.length / 2];

        long durationNanos = last.tsNanos - first.tsNanos;
        double durationSec = durationNanos / 1_000_000_000.0;
        double priceDollars = first.priceRaw / 10_000.0;
        double notionalDollars = totalShares * priceDollars;

        ObjectMapper json = ctx.json();
        ObjectNode breakdown = json.createObjectNode();
        breakdown.put("fills",            BreakdownFmt.formatCount(run.size()));
        breakdown.put("total_shares",     BreakdownFmt.formatCount(totalShares));
        breakdown.put("price_dollars",    priceDollars);
        breakdown.put("median_fill_size", BreakdownFmt.formatCount(medianSize));
        breakdown.put("size_cv",          BreakdownFmt.round(cv, 3));
        // Display ratio: median displayed tip as a % of the total worked. Small =
        // heavily concealed (a tiny tip refilling into a large total). The grounded
        // stand-in for "implied reserve" — both numbers observed, no hidden-size guess.
        if (totalShares > 0) breakdown.put("display_ratio_pct", BreakdownFmt.round(medianSize * 100.0 / totalShares, 2));
        breakdown.put("duration",         BreakdownFmt.durationNanos(durationNanos));
        breakdown.put("start_et",         BreakdownFmt.toEtTime(first.ts));
        breakdown.put("end_et",           BreakdownFmt.toEtTime(last.ts));

        // ─── derived fields (DETECT enrichment, 2026-05-22) ──────────────
        breakdown.put("duration_seconds",       BreakdownFmt.round(durationSec, 1));
        // Pluralization-safe phrasing for prose (see LiquidityWithdrawalScorer).
        breakdown.put("duration_humanized",     BreakdownFmt.durationSecHumanized(Math.round(durationSec)));
        breakdown.put("notional_dollars",       BreakdownFmt.formatDollars(notionalDollars));
        breakdown.put("notional_per_fill",      BreakdownFmt.formatDollars(notionalDollars / run.size()));
        breakdown.put("inter_fill_seconds_avg", BreakdownFmt.round(durationSec / run.size(), 2));
        // Refill-cadence regularity: CV of the inter-fill gaps. Low = metronomic
        // refills (a machine-worked reserve); high = irregular. Distinct from
        // size_cv (dispersion of fill *sizes*, not their timing).
        if (run.size() >= 3) {
            double[] gaps = new double[run.size() - 1];
            for (int i = 1; i < run.size(); i++) gaps[i - 1] = run.get(i).tsNanos - run.get(i - 1).tsNanos;
            double cadenceCv = com.longexposure.analytics.Analytics.coefficientOfVariation(gaps);
            if (!Double.isNaN(cadenceCv)) {
                breakdown.put("refill_cadence_cv", BreakdownFmt.round(cadenceCv, 3));
                // Anchored label so the prose has vocabulary ("metronomic" /
                // "regular" / "irregular" / "erratic") instead of bare "CV 2.57"
                // — same pattern as burstiness_class / ofi_class.
                String cls = com.longexposure.analytics.Analytics.refillCadenceClass(cadenceCv);
                if (cls != null) breakdown.put("refill_cadence_class", cls);
            }
        }
        breakdown.put("fill_size_uniformity",
                cv < 0.05 ? "very_uniform" :
                cv < 0.20 ? "uniform" : "mixed");
        breakdown.put("event_session_phase",    BreakdownFmt.sessionPhase(first.ts));
        breakdown.put("event_phase_label",      BreakdownFmt.sessionPhaseLabel(first.ts));

        ArrayNode sourceRefs = json.createArrayNode();
        int refsToEmit = Math.min(run.size(), MAX_SOURCE_REFS);
        for (int i = 0; i < refsToEmit; i++) {
            Fill f = run.get(i);
            ObjectNode ref = json.createObjectNode();
            ref.put("table",    "orders_executed");
            ref.put("symbol",   f.symbol);
            ref.put("ts_nanos", f.tsNanos);
            ref.put("order_id", f.orderId);
            ref.put("trade_id", f.tradeId);
            sourceRefs.add(ref);
        }
        if (run.size() > MAX_SOURCE_REFS) {
            ObjectNode trunc = json.createObjectNode();
            trunc.put("truncated_count", run.size() - MAX_SOURCE_REFS);
            sourceRefs.add(trunc);
        }

        com.longexposure.scoring.SymbolFields.apply(breakdown, ctx, first.symbol);

        // Phase 7c TOD weight: iceberg anchored at first fill (when the
        // pattern became detectable on the wire).
        double score = Math.log10(Math.max(totalShares, 1)) * run.size()
                       * BreakdownFmt.timeOfDayWeight(first.ts);

        return new ScoredEvent(
                ctx.tradingDate(),
                first.symbol,
                first.ts,
                last.ts,
                "iceberg",
                score,
                breakdown,
                sourceRefs);
    }

    private record Fill(
            Instant ts,
            long    tsNanos,
            String  symbol,
            long    priceRaw,
            int     size,
            long    orderId,
            long    tradeId) {}
}
