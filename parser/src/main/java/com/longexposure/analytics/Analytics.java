package com.longexposure.analytics;

/**
 * Pure, deterministic microstructure/statistical computations fed into event
 * breakdowns for the LLM to narrate. The "supply side" of the
 * code-computes-LLM-wraps contract (see {@code docs/analytics-catalog.md}):
 * scorers/enrichment call these to pre-compute the analytics the prose then
 * states — the LLM never computes, only renders.
 *
 * <p>Everything here is a pure function of its inputs (no I/O, no state), so
 * each is trivially unit-testable in isolation — which is exactly why
 * computation lives here and not in the LLM. Domain functions
 * ({@link #slippageBps}, {@link #oneSidedness}, …) are the launch Tier-1 set;
 * the statistical helpers ({@link #median}, {@link #mad}, {@link #robustZ},
 * {@link #percentileRank}) back the inter-day metrics and are reused as more
 * scorers come online.
 *
 * <p>Prices are the IEX wire encoding: signed integer, 4 implied decimals
 * ({@code 1032500} = $103.25). bps = basis points = 1/100 of a percent.
 */
public final class Analytics {

    private Analytics() {}

    // ─── domain metrics (Tier 1) ──────────────────────────────────────────────

    /**
     * Signed price slippage of an order walking the book, in basis points:
     * how far the execution price moved from the first fill to the last.
     * Positive = walked up (an aggressive buy paying into higher offers);
     * negative = walked down. Magnitude is the "cost of impatience" a sweep paid.
     *
     * <p>Inputs are raw wire prices (4 implied decimals); the ratio is
     * scale-invariant so no decoding is needed. Returns {@code 0.0} if the
     * first price is non-positive (degenerate; a real fill is always > 0).
     */
    public static double slippageBps(final long firstPriceRaw, final long lastPriceRaw) {
        if (firstPriceRaw <= 0) return 0.0;
        return (double) (lastPriceRaw - firstPriceRaw) / firstPriceRaw * 10_000.0;
    }

    /** One-sidedness of a cluster: the dominant side's share of orders, in [0.5, 1.0]. */
    public record OneSidedness(double ratio, String dominant) {}

    /**
     * How lopsided a cluster of orders is between buy and sell side.
     * {@code ratio} = max(buy, sell) / total ∈ [0.5, 1.0] (1.0 = entirely one
     * side, 0.5 = perfectly balanced); {@code dominant} ∈ {"buy", "sell",
     * "balanced"}. Quantifies the manipulation *shape* (one-sided layering),
     * never intent. Returns {@code (0.0, "none")} when there are no orders.
     */
    public static OneSidedness oneSidedness(final long buyCount, final long sellCount) {
        long total = buyCount + sellCount;
        if (total <= 0) return new OneSidedness(0.0, "none");
        String dominant = buyCount > sellCount ? "buy" : sellCount > buyCount ? "sell" : "balanced";
        double ratio = Math.max(buyCount, sellCount) / (double) total;
        return new OneSidedness(ratio, dominant);
    }

    /**
     * A quantity as a percentage of a per-symbol baseline (e.g. a block's size
     * as a % of the symbol's median daily IEX volume — "a fifth of the day in
     * one print"). Returns {@link Double#NaN} if the baseline is non-positive
     * (caller should omit the field rather than narrate NaN).
     */
    public static double pctOfBaseline(final long value, final double baseline) {
        if (baseline <= 0) return Double.NaN;
        return value / baseline * 100.0;
    }

    // ─── statistical helpers (inter-day deviation) ────────────────────────────

    /**
     * Robust z-score: how many robust standard deviations {@code value} sits
     * from the {@code median}, using MAD scaled by 1.4826 (the consistency
     * factor that makes MAD a σ-estimator under normality). Robust to the
     * outliers that make a mean/stddev z-score useless on bursty volume.
     * Returns {@code 0.0} if MAD is non-positive (no dispersion to measure).
     */
    public static double robustZ(final double value, final double median, final double mad) {
        if (mad <= 0) return 0.0;
        return (value - median) / (1.4826 * mad);
    }

    /** Median of the values (does not mutate the input). Returns NaN if empty. */
    public static double median(final double[] values) {
        if (values == null || values.length == 0) return Double.NaN;
        double[] copy = values.clone();
        java.util.Arrays.sort(copy);
        int n = copy.length;
        return (n % 2 == 1) ? copy[n / 2] : (copy[n / 2 - 1] + copy[n / 2]) / 2.0;
    }

    /** Median absolute deviation from the median. Returns NaN if empty. */
    public static double mad(final double[] values) {
        if (values == null || values.length == 0) return Double.NaN;
        double med = median(values);
        double[] dev = new double[values.length];
        for (int i = 0; i < values.length; i++) dev[i] = Math.abs(values[i] - med);
        return median(dev);
    }

    /**
     * Percentile rank of {@code value} within {@code values}: the fraction
     * (×100) of entries strictly less than {@code value}, in [0, 100].
     * "today is in the 96th percentile of the trailing window." Returns NaN if
     * empty.
     */
    public static double percentileRank(final double value, final double[] values) {
        if (values == null || values.length == 0) return Double.NaN;
        int below = 0;
        for (double v : values) if (v < value) below++;
        return below / (double) values.length * 100.0;
    }

    // ─── shape / flow / timing metrics ────────────────────────────────────────

    /**
     * Coefficient of variation (sample stddev / mean) — a unitless dispersion
     * measure. Small = highly uniform (machine-regular inter-fill gaps, or
     * equal-size iceberg fills); large = irregular. Uses the sample stddev
     * (n−1). Returns NaN if fewer than 2 values or the mean is non-positive
     * (CV is undefined there).
     */
    public static double coefficientOfVariation(final double[] values) {
        if (values == null || values.length < 2) return Double.NaN;
        double mean = 0.0;
        for (double v : values) mean += v;
        mean /= values.length;
        if (mean <= 0) return Double.NaN;
        double ss = 0.0;
        for (double v : values) { double d = v - mean; ss += d * d; }
        return Math.sqrt(ss / (values.length - 1)) / mean;
    }

    /**
     * Fano factor (burstiness) of event arrival times: variance/mean of the
     * per-bin event counts when the [min, max] timestamp span is split into
     * {@code bins} equal sub-intervals. ≈1 = Poisson (random arrivals); ≫1 =
     * bursty / clustered (the machine-paced post-cancel signature); &lt;1 =
     * more regular than random. Returns NaN if fewer than 2 events, a
     * non-positive span, or {@code bins < 2}.
     */
    public static double fanoFactor(final long[] tsNanos, final int bins) {
        if (tsNanos == null || tsNanos.length < 2 || bins < 2) return Double.NaN;
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (long t : tsNanos) { if (t < min) min = t; if (t > max) max = t; }
        long span = max - min;
        if (span <= 0) return Double.NaN;
        int[] counts = new int[bins];
        for (long t : tsNanos) {
            int b = (int) ((double) (t - min) / span * bins);
            if (b >= bins) b = bins - 1;          // the max timestamp lands in the last bin
            counts[b]++;
        }
        double mean = 0.0;
        for (int c : counts) mean += c;
        mean /= bins;
        if (mean <= 0) return Double.NaN;
        double ss = 0.0;
        for (int c : counts) { double d = c - mean; ss += d * d; }
        return (ss / bins) / mean;                // population variance of bin counts / mean
    }

    /**
     * Post-event price reversion as a fraction of the event's own impact:
     * {@code (impactPrice − postPrice) / (impactPrice − preImpactPrice)}.
     * 1.0 = price fully returned to where it started (transient impact — the
     * aggressor overpaid into thin liquidity); 0.0 = no reversion (permanent
     * impact, consistent with informed flow); negative = price continued in
     * the impact direction. Returns NaN if the impact (denominator) is zero.
     */
    public static double reversionFraction(final double preImpactPrice,
                                           final double impactPrice,
                                           final double postPrice) {
        double impact = impactPrice - preImpactPrice;
        if (impact == 0.0) return Double.NaN;
        return (impactPrice - postPrice) / impact;
    }

    /**
     * Effective spread in basis points: {@code 2 × |exec − mid| / mid × 10⁴} —
     * the round-trip cost paid relative to the prevailing book mid at
     * execution. Inputs are decoded dollar prices. Returns NaN if mid is
     * non-positive.
     */
    public static double effectiveSpreadBps(final double execPrice, final double midPrice) {
        if (midPrice <= 0) return Double.NaN;
        return 2.0 * Math.abs(execPrice - midPrice) / midPrice * 10_000.0;
    }

    /**
     * Order-to-trade ratio: orders posted per resulting trade — the regulator's
     * manipulation-shape metric, and the quantified spoof shape ("131 orders, 0
     * fills"). Returns {@link Double#POSITIVE_INFINITY} when there were no
     * trades (all posted, none filled — the caller should narrate "0 fills"
     * rather than "∞"), and NaN if there were no orders.
     */
    public static double orderToTradeRatio(final long orders, final long trades) {
        if (orders <= 0) return Double.NaN;
        if (trades <= 0) return Double.POSITIVE_INFINITY;
        return orders / (double) trades;
    }
}
