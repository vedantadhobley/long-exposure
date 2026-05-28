package com.longexposure.scoring.scorers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.longexposure.analytics.Analytics;
import com.longexposure.scoring.BaselineProvider;
import com.longexposure.scoring.BreakdownFmt;
import com.longexposure.scoring.EventScorer;
import com.longexposure.scoring.ScoredEvent;
import com.longexposure.scoring.ScoringContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Inter-day time-in-book-drift detector — the second scorer that reads beyond
 * a single trading day (after {@link VolumeDeviationScorer}). For each symbol,
 * compares the day's <em>average terminal-order lifetime</em> against the
 * median of that symbol's daily average lifetimes over the trailing window,
 * and emits when the lifetime regime shifted materially in either direction:
 *
 * <ul>
 *   <li><b>collapsed</b> — orders living far shorter than the norm (the
 *       canonical story: "median order lifetime on SPY collapsed from 800 ms to
 *       90 ms" — a shift toward cancel-heavy / faster participants), and
 *   <li><b>stretched</b> — orders living far longer (resting liquidity, fewer
 *       fast cancellations).
 * </ul>
 *
 * <p>Source: the {@code daily_lifetime_by_symbol} table (one row per symbol per
 * day: {@code avg_lifetime_ns}, {@code order_count}), populated by
 * {@code MaterializeOrderLifecycleActivity} right after it rebuilds
 * {@code order_lifecycle} for the day. It persists indefinitely (a small,
 * durable baseline that outlives the 2-week wire retention, like
 * {@code daily_volume_by_symbol}). Read through {@link BaselineProvider} — the
 * scorer holds only policy (gates + score), no SQL.
 *
 * <p>Emission gates (all must hold), tuned so events are narratable regime
 * shifts rather than thin-name noise:
 * <ul>
 *   <li>{@code >= MIN_BASELINE_DAYS} prior days, so the median is meaningful
 *   <li>{@code baseline_median_lifetime >= MIN_BASELINE_LIFETIME_NS} — anti
 *       divide-by-tiny: without a floor a sub-microsecond baseline makes the
 *       drift ratio explode
 *   <li>{@code today_order_count >= MIN_ORDERS_TODAY} — the day's average must
 *       be over enough terminal orders to be a stable statistic
 *   <li>drift magnitude {@code >= MIN_DRIFT} (max of today/baseline and its
 *       inverse, so both collapse and stretch are caught symmetrically)
 * </ul>
 *
 * <p>Score = the drift magnitude (ranks the most-extreme regime shift first
 * within this scorer; cross-scorer comparison is the selector's percentile-rank
 * job, so the raw unit need not match other scorers).
 *
 * <p>Breakdown carries BOTH today's lifetime and the baseline (the grounding
 * contract — the narrator must be able to say "4.2× shorter than its 2-week
 * norm" with both figures present), the direction, and the pre-computed
 * magnitude so the LLM never divides at inference.
 */
public final class TimeInBookDriftScorer implements EventScorer {

    private static final Logger LOG = LoggerFactory.getLogger(TimeInBookDriftScorer.class);

    /** Trailing calendar-day window the baseline median is computed over. */
    private static final int BASELINE_WINDOW_DAYS = 14;

    /** Minimum prior days within the window for the median to be meaningful. */
    private static final int MIN_BASELINE_DAYS = 3;

    /**
     * Floor on the BASELINE median lifetime. Anti divide-by-tiny: a symbol whose
     * orders normally live sub-microsecond would otherwise produce an exploding
     * drift ratio off a near-zero denominator. 1 μs ≈ a genuine resting-then-
     * cancelled order rather than a same-instant add/cancel artifact.
     */
    private static final long MIN_BASELINE_LIFETIME_NS = 1_000L;

    /** Today's terminal-order count floor — the day's average must be stable. */
    private static final long MIN_ORDERS_TODAY = 1_000L;

    /** Minimum drift magnitude (in either direction) to surface as an event. */
    private static final double MIN_DRIFT = 3.0;

    @Override
    public String id() { return "time_in_book_drift"; }

    @Override
    public void score(final ScoringContext ctx, final Consumer<ScoredEvent> emit) {
        // All baseline access goes through the BaselineProvider — the scorer holds
        // only policy. Three bulk reads (today's per-symbol avg lifetime, each
        // symbol's trailing median, and the raw window for robust-z); all are
        // ~8.8k tiny entries so this is trivial. Same shape as VolumeDeviation.
        BaselineProvider baselines = ctx.baselines();
        Instant dayStart = ctx.tradingDate().atStartOfDay().toInstant(ZoneOffset.UTC);

        Map<String, BaselineProvider.DayLifetime> today =
                baselines.dayLifetimes(ctx.tradingDate());
        Map<String, BaselineProvider.TrailingLifetime> baseline =
                baselines.trailingLifetimeBaselines(ctx.tradingDate(), BASELINE_WINDOW_DAYS);
        Map<String, double[]> windows =
                baselines.trailingLifetimeWindows(ctx.tradingDate(), BASELINE_WINDOW_DAYS);

        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<String, BaselineProvider.DayLifetime> e : today.entrySet()) {
            String symbol = e.getKey();
            BaselineProvider.DayLifetime t = e.getValue();
            BaselineProvider.TrailingLifetime b = baseline.get(symbol);
            if (b == null) continue;                                  // JOIN today ∩ baseline

            if (b.dayCount()        <  MIN_BASELINE_DAYS)        continue;
            if (b.medianLifetimeNs() < MIN_BASELINE_LIFETIME_NS) continue;
            if (t.orderCount()      <  MIN_ORDERS_TODAY)         continue;

            double ratio = t.avgLifetimeNs() / b.medianLifetimeNs();   // today / baseline
            double magnitude = Math.max(ratio, 1.0 / ratio);           // symmetric
            if (magnitude < MIN_DRIFT) continue;

            hits.add(new Hit(symbol, t.avgLifetimeNs(), t.orderCount(),
                    b.medianLifetimeNs(), b.dayCount(), ratio, magnitude,
                    windows.get(symbol)));
        }
        hits.sort(Comparator.comparingDouble((Hit h) -> h.magnitude).reversed());

        long emitted = 0;
        for (Hit h : hits) {
            emit.accept(buildEvent(ctx, h, dayStart));
            emitted++;
            if (emitted % 50 == 0) ctx.heartbeat().send("time_in_book_drift:" + emitted);
        }

        LOG.info("TimeInBookDriftScorer  date={} window_days={} min_drift={}x emitted={}",
                ctx.tradingDate(), BASELINE_WINDOW_DAYS, MIN_DRIFT, emitted);
    }

    /** One qualifying drift — the joined today+baseline lifetime figures for a symbol. */
    private record Hit(String symbol, long todayLifeNs, long todayOrders,
                       double baseLifeNs, int days, double ratio, double magnitude,
                       double[] window) {}

    private static ScoredEvent buildEvent(final ScoringContext ctx, final Hit h,
                                          final Instant dayStart) {
        String symbol = h.symbol();
        ObjectMapper json = ctx.json();
        ObjectNode breakdown = json.createObjectNode();

        // "collapsed" (shorter) vs "stretched" (longer) — the human-facing
        // direction the narration leads with. ratio<1 => today shorter than norm.
        boolean shorter = h.ratio() < 1.0;
        breakdown.put("todays_avg_lifetime",       BreakdownFmt.durationNanos(h.todayLifeNs()));
        breakdown.put("baseline_median_lifetime",  BreakdownFmt.durationNanos(Math.round(h.baseLifeNs())));
        breakdown.put("drift_x",                    BreakdownFmt.round(h.magnitude(), 1));
        breakdown.put("drift_direction",            shorter ? "shorter" : "longer");
        breakdown.put("baseline_window_trading_days", h.days());
        breakdown.put("todays_order_count",         BreakdownFmt.formatCount(h.todayOrders()));

        // Robust deviation of today's avg lifetime within the trailing window —
        // "how anomalous", not just the bare multiple. Sign of z encodes direction.
        double[] window = h.window();
        if (window != null && window.length >= MIN_BASELINE_DAYS) {
            double mad = Analytics.mad(window);
            double z   = Analytics.robustZ(h.todayLifeNs(), h.baseLifeNs(), mad);
            double pct = Analytics.percentileRank(h.todayLifeNs(), window);
            if (mad > 0)             breakdown.put("robust_z", BreakdownFmt.round(z, 1));
            if (!Double.isNaN(pct))  breakdown.put("percentile_rank", BreakdownFmt.round(pct, 0));
            double cp = Analytics.cusumShift(window);
            if (!Double.isNaN(cp))   breakdown.put("lifetime_regime_shift", BreakdownFmt.round(cp, 2));
        }

        ArrayNode sourceRefs = json.createArrayNode();
        ObjectNode ref = json.createObjectNode();
        ref.put("table",  "daily_lifetime_by_symbol");
        ref.put("symbol", symbol);
        ref.put("day",    ctx.tradingDate().toString());
        sourceRefs.add(ref);

        com.longexposure.scoring.SymbolFields.apply(breakdown, ctx, symbol);

        return new ScoredEvent(
                ctx.tradingDate(),
                symbol,
                dayStart,          // whole-day event — anchor at the day's UTC start
                null,
                "time_in_book_drift",
                h.magnitude(),     // score = drift magnitude
                breakdown,
                sourceRefs);
    }
}
