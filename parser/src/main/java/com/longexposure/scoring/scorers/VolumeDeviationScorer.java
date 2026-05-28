package com.longexposure.scoring.scorers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Inter-day volume-deviation detector — the first scorer that reads beyond
 * a single trading day. For each symbol, compares the day's total traded
 * volume against the median of that symbol's volume over the trailing
 * window, and emits when today is a significant surge.
 *
 * <p>Source: the {@code daily_volume_by_symbol} continuous aggregate (one
 * row per symbol per day: {@code total_volume = SUM(size)} over the
 * {@code trades} table, {@code trade_count}). The baseline is the median
 * of prior days' {@code total_volume} within {@link #BASELINE_WINDOW_DAYS}
 * (median, not mean, so a single prior spike doesn't wash out the
 * baseline). Aligns naturally with the week-aligned 2-week retention —
 * the window holds whatever complete days are retained.
 *
 * <p>Emission gates (all must hold), tuned so the events are narratable
 * rather than illiquid-ticker noise:
 * <ul>
 *   <li>{@code >= MIN_BASELINE_DAYS} prior days, so the median is meaningful
 *   <li>{@code baseline_median >= MIN_BASELINE_SHARES} — the load-bearing
 *       one: without a baseline floor the ratio explodes for near-zero
 *       baselines (a 10-share/day name showing 117k reads as 11,706×)
 *   <li>{@code today_volume >= MIN_VOLUME_SHARES} absolute floor
 *   <li>{@code deviation_x >= MIN_DEVIATION} (today / baseline-median)
 * </ul>
 *
 * <p>Score = {@code deviation_x} (ranks the most-anomalous surge first
 * within this scorer; cross-scorer comparison is handled by the selector's
 * per-scorer percentile rank, so the raw unit need not match other
 * scorers).
 *
 * <p>Breakdown carries BOTH today's number and the baseline (the grounding
 * contract for an inter-day claim — the narrator must be able to say
 * "4.7× its 2-week median" with both figures present), plus the
 * pre-computed {@code deviation_x} so the LLM never divides at inference.
 *
 * <p>v1 flags surges only (high volume). Volume *droughts* (today far below
 * baseline) are a separate, less-narratable signal; deferred.
 */
public final class VolumeDeviationScorer implements EventScorer {

    private static final Logger LOG = LoggerFactory.getLogger(VolumeDeviationScorer.class);

    /** Trailing calendar-day window the baseline median is computed over. */
    private static final int BASELINE_WINDOW_DAYS = 14;

    /** Minimum prior days within the window for the median to be meaningful. */
    private static final int MIN_BASELINE_DAYS = 3;

    /** Absolute floor on today's volume — keeps illiquid-ticker noise out. */
    private static final long MIN_VOLUME_SHARES = 100_000L;

    /**
     * Floor on the BASELINE median. Load-bearing: ranking by ratio explodes
     * for near-zero baselines — a name that normally trades 10 shares/day on
     * IEX showing 117k once reads as an 11,706× "surge", which is noise, not a
     * story. Requiring the baseline itself to be a real volume (≥25k IEX
     * shares/day ≈ a genuinely-traded small/mid-cap, since IEX is ~3% of
     * consolidated volume) keeps the divide-by-tiny artifacts out. Validated
     * against 2026-05-22: without it the top 8 were sub-5k-baseline noise;
     * with it the top events are FUTU/TIGR/SPCE/PTON/NTAP-class real surges.
     */
    private static final long MIN_BASELINE_SHARES = 25_000L;

    /** Minimum today/baseline ratio to surface as an event. */
    private static final double MIN_DEVIATION = 3.0;

    @Override
    public String id() { return "volume_deviation"; }

    @Override
    public void score(final ScoringContext ctx, final Consumer<ScoredEvent> emit) {
        // All cagg access goes through the BaselineProvider now — the scorer
        // holds only policy (the gates + the score formula), not SQL. Two bulk
        // reads: today's per-symbol volume snapshot, and each symbol's median
        // over the trailing [D-window, D) window. Joined + gated in Java; both
        // maps are ~8.8k tiny entries so this is trivial.
        BaselineProvider baselines = ctx.baselines();
        Instant dayStart = ctx.tradingDate().atStartOfDay().toInstant(ZoneOffset.UTC);

        Map<String, BaselineProvider.DayVolume> today =
                baselines.dayVolumes(ctx.tradingDate());
        Map<String, BaselineProvider.TrailingVolume> baseline =
                baselines.trailingVolumeBaselines(ctx.tradingDate(), BASELINE_WINDOW_DAYS);
        // Raw window values per symbol → robust z-score (MAD-scaled) + percentile
        // rank, computed in Java via Analytics. "6σ above its fortnight norm, the
        // busiest day in the window" — *how* anomalous, not just the bare ratio.
        Map<String, double[]> windows =
                baselines.trailingVolumeWindows(ctx.tradingDate(), BASELINE_WINDOW_DAYS);

        // Collect the qualifying surges, then emit in ratio-descending order
        // (preserves the prior SQL's ORDER BY (today_vol / med_vol) DESC).
        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<String, BaselineProvider.DayVolume> e : today.entrySet()) {
            String symbol = e.getKey();
            BaselineProvider.DayVolume t = e.getValue();
            BaselineProvider.TrailingVolume b = baseline.get(symbol);
            if (b == null) continue;                              // JOIN today ∩ baseline

            // Gates — identical to the prior WHERE clause. medVol >= floor > 0,
            // so the ratio test below is the algebraic equivalent of the SQL's
            // today_vol >= MIN_DEVIATION * med_vol.
            if (b.dayCount()   <  MIN_BASELINE_DAYS)   continue;
            if (b.medianVolume() < MIN_BASELINE_SHARES) continue;
            if (t.volume()     <  MIN_VOLUME_SHARES)   continue;
            double deviationX = t.volume() / b.medianVolume();
            if (deviationX     <  MIN_DEVIATION)       continue;

            hits.add(new Hit(symbol, t.volume(), t.tradeCount(),
                    b.medianVolume(), b.medianTradeCount(), b.dayCount(), deviationX,
                    windows.get(symbol)));
        }
        hits.sort(Comparator.comparingDouble((Hit h) -> h.deviationX).reversed());

        long emitted = 0;
        for (Hit h : hits) {
            emit.accept(buildEvent(ctx, h, dayStart));
            emitted++;
            if (emitted % 50 == 0) ctx.heartbeat().send("volume_deviation:" + emitted);
        }

        LOG.info("VolumeDeviationScorer  date={} window_days={} min_dev={}x emitted={}",
                ctx.tradingDate(), BASELINE_WINDOW_DAYS, MIN_DEVIATION, emitted);
    }

    /** One qualifying surge — the joined today+baseline figures for a symbol. */
    private record Hit(String symbol, long todayVol, long todayTc,
                       double medVol, double medTc, int days, double deviationX,
                       double[] window) {}

    private static ScoredEvent buildEvent(final ScoringContext ctx, final Hit h,
                                          final Instant dayStart) {
        String symbol   = h.symbol();
        long   todayVol  = h.todayVol();
        long   todayTc   = h.todayTc();
        double medVol    = h.medVol();
        double medTc     = h.medTc();
        int    days      = h.days();
        double deviationX = h.deviationX();

        ObjectMapper json = ctx.json();
        ObjectNode breakdown = json.createObjectNode();
        breakdown.put("todays_volume_shares",         BreakdownFmt.formatCount(todayVol));
        breakdown.put("baseline_median_shares",       BreakdownFmt.formatCount(Math.round(medVol)));
        breakdown.put("baseline_window_trading_days",  days);
        breakdown.put("deviation_x",                   BreakdownFmt.round(deviationX, 1));
        breakdown.put("todays_trade_count",            BreakdownFmt.formatCount(todayTc));
        breakdown.put("baseline_median_trade_count",   BreakdownFmt.formatCount(Math.round(medTc)));

        // Robust deviation: how many MAD-scaled σ today sits above the window
        // median, + its percentile rank within the window. "6σ above its norm,
        // the busiest in the fortnight" — distributional, not a bare multiple.
        double[] window = h.window();
        if (window != null && window.length >= MIN_BASELINE_DAYS) {
            double mad = com.longexposure.analytics.Analytics.mad(window);
            double z   = com.longexposure.analytics.Analytics.robustZ(todayVol, medVol, mad);
            double pct = com.longexposure.analytics.Analytics.percentileRank(todayVol, window);
            if (mad > 0) breakdown.put("robust_z", BreakdownFmt.round(z, 1));
            if (!Double.isNaN(pct)) breakdown.put("percentile_rank", BreakdownFmt.round(pct, 0));
            // Change-point on the symbol's own trailing volume series — did its
            // volume regime shift over the window (a sustained step), vs a one-day spike?
            double cp = com.longexposure.analytics.Analytics.cusumShift(window);
            if (!Double.isNaN(cp)) breakdown.put("volume_regime_shift", BreakdownFmt.round(cp, 2));
        }

        ArrayNode sourceRefs = json.createArrayNode();
        ObjectNode ref = json.createObjectNode();
        ref.put("table",  "daily_volume_by_symbol");
        ref.put("symbol", symbol);
        ref.put("day",    ctx.tradingDate().toString());
        sourceRefs.add(ref);

        com.longexposure.scoring.SymbolFields.apply(breakdown, ctx, symbol);

        return new ScoredEvent(
                ctx.tradingDate(),
                symbol,
                dayStart,         // whole-day event — anchor at the day's UTC start
                null,
                "volume_deviation",
                deviationX,       // score = the deviation ratio
                breakdown,
                sourceRefs);
    }
}
