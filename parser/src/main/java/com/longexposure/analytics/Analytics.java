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

    // ─── volatility / price-path structure ────────────────────────────────────

    /**
     * Realized volatility over a price path: √(Σ squared log-returns) between
     * consecutive positive prices. Unitless (a fraction; ×100 for %). The
     * window's total realized move, not annualized. NaN if &lt;2 usable prices.
     */
    public static double realizedVolatility(final double[] prices) {
        if (prices == null || prices.length < 2) return Double.NaN;
        double ss = 0.0; int n = 0;
        for (int i = 1; i < prices.length; i++) {
            if (prices[i] > 0 && prices[i - 1] > 0) {
                double r = Math.log(prices[i] / prices[i - 1]);
                ss += r * r; n++;
            }
        }
        return n == 0 ? Double.NaN : Math.sqrt(ss);
    }

    /**
     * Bipower-variation jump ratio: {@code (RV − BV) / RV}, where RV = Σr² and
     * BV = (π/2)·Σ|rᵢ||rᵢ₋₁|. ~0 = continuous diffusion; →1 = dominated by
     * discrete jumps. Clamped to [0, 1]. NaN if &lt;3 returns or RV = 0.
     */
    public static double jumpRatio(final double[] returns) {
        if (returns == null || returns.length < 3) return Double.NaN;
        double rv = 0.0;
        for (double r : returns) rv += r * r;
        if (rv <= 0) return Double.NaN;
        double bv = 0.0;
        for (int i = 1; i < returns.length; i++) bv += Math.abs(returns[i]) * Math.abs(returns[i - 1]);
        bv *= Math.PI / 2.0;
        return Math.max(0.0, Math.min(1.0, (rv - bv) / rv));
    }

    // ─── concentration / breadth across a set (day-level) ─────────────────────

    /** Herfindahl-Hirschman index: Σ(valueᵢ / total)² ∈ (0, 1]. 1 = all in one; →0 = even spread. */
    public static double hhi(final double[] values) {
        if (values == null || values.length == 0) return Double.NaN;
        double total = 0.0;
        for (double v : values) total += Math.max(v, 0);
        if (total <= 0) return Double.NaN;
        double h = 0.0;
        for (double v : values) { double s = Math.max(v, 0) / total; h += s * s; }
        return h;
    }

    /** Shannon entropy of a distribution, normalized to [0, 1] by log(k): 1 = uniform, 0 = concentrated. */
    public static double normalizedEntropy(final double[] counts) {
        if (counts == null || counts.length < 2) return Double.NaN;
        double total = 0.0; int k = 0;
        for (double c : counts) if (c > 0) { total += c; k++; }
        if (total <= 0 || k < 2) return Double.NaN;
        double h = 0.0;
        for (double c : counts) if (c > 0) { double p = c / total; h -= p * Math.log(p); }
        return h / Math.log(k);
    }

    // ─── temporal structure ───────────────────────────────────────────────────

    /**
     * Lag-1 autocorrelation of a series ∈ [−1, 1] — a periodicity / machine-cadence
     * detector: regular inter-arrival gaps autocorrelate. NaN if &lt;3 points or
     * zero variance.
     */
    public static double lag1Autocorrelation(final double[] x) {
        if (x == null || x.length < 3) return Double.NaN;
        double mean = 0.0; for (double v : x) mean += v; mean /= x.length;
        double num = 0.0, den = 0.0;
        for (int i = 0; i < x.length; i++) {
            double d = x[i] - mean; den += d * d;
            if (i > 0) num += d * (x[i - 1] - mean);
        }
        return den == 0 ? Double.NaN : num / den;
    }

    /**
     * CUSUM change-point magnitude: the maximum absolute cumulative deviation
     * from the series mean, normalized by (n · stddev). ~0 = stationary; larger
     * = a sustained level shift somewhere in the series. NaN if &lt;3 points or
     * zero variance.
     */
    public static double cusumShift(final double[] x) {
        if (x == null || x.length < 3) return Double.NaN;
        double mean = 0.0; for (double v : x) mean += v; mean /= x.length;
        double var = 0.0; for (double v : x) { double d = v - mean; var += d * d; } var /= x.length;
        double sd = Math.sqrt(var);
        if (sd <= 0) return Double.NaN;
        double cum = 0.0, maxAbs = 0.0;
        for (double v : x) { cum += v - mean; maxAbs = Math.max(maxAbs, Math.abs(cum)); }
        return maxAbs / (x.length * sd);
    }

    /**
     * Hawkes branching-ratio estimate from the Fano factor (moment-based, not
     * MLE): {@code n ≈ 1 − 1/√Fano} ∈ [0, 1) — the fraction of events
     * "triggered" by prior events (self-excitation). A more interpretable
     * framing of burstiness. NaN if Fano ≤ 1 (no excitation to estimate).
     */
    public static double branchingRatioFromFano(final double fano) {
        if (Double.isNaN(fano) || fano <= 1.0) return Double.NaN;
        return Math.min(0.999, 1.0 - 1.0 / Math.sqrt(fano));
    }

    /**
     * Map a Fano factor to a human-readable class label, so the LLM has an
     * anchored phrase to lead with (and falls back to the bare number only as
     * a parenthetical). The bands match the standard burstiness interpretation:
     * Fano = 1 ≡ Poisson-random arrivals; &gt;1 = overdispersed (clustered);
     * &lt;1 = underdispersed (regular / machine-paced).
     *
     * <p>Returns {@code null} if Fano is NaN (caller skips the field).
     */
    public static String fanoClass(final double fano) {
        if (Double.isNaN(fano)) return null;
        if (fano > 5.0) return "highly bursty";
        if (fano > 2.0) return "moderately bursty";
        if (fano > 1.0) return "weakly bursty";
        return "Poisson-like";
    }

    /**
     * Anchored direction label for an order-flow-imbalance value in [−1, +1].
     * Positive = buy-side accumulation; negative = sell-side. Magnitudes below
     * 0.1 are informationally close to a balanced book — render as "balanced"
     * (or omit upstream, per the framing rule).
     *
     * <p>Returns {@code null} for NaN.
     */
    public static String ofiClass(final double ofi) {
        if (Double.isNaN(ofi)) return null;
        if (ofi > 0.1)  return "buyer-leaning";
        if (ofi < -0.1) return "seller-leaning";
        return "balanced";
    }

    /**
     * Anchored direction label for a displayed-depth imbalance value in
     * [−1, +1]. Positive = bid-side heavier; negative = ask-side heavier;
     * |·| &lt; 0.1 = roughly balanced book.
     *
     * <p>Returns {@code null} for NaN.
     */
    public static String depthImbalanceClass(final double imb) {
        if (Double.isNaN(imb)) return null;
        if (imb > 0.1)  return "bid-skewed";
        if (imb < -0.1) return "ask-skewed";
        return "balanced";
    }

    /**
     * Anchored class label for a refill-cadence CV (iceberg inter-fill gap
     * variability). Low CV = same gap between every refill (machine-worked
     * reserve); high CV = irregular timing. Bands tuned for inter-fill
     * coefficients-of-variation observed empirically on the 2-week dataset.
     *
     * <p>Returns {@code null} for NaN.
     */
    public static String refillCadenceClass(final double cv) {
        if (Double.isNaN(cv)) return null;
        if (cv < 0.3) return "metronomic";
        if (cv < 1.0) return "regular";
        if (cv < 2.0) return "irregular";
        return "erratic";
    }

    // ─── signed-flow / impact (IEX-slice approximations — narrate with that caveat) ──

    /** Order-flow imbalance: net signed displayed-size change ∈ [−1, 1]; +1 = all bid-side accumulation. */
    public static double orderFlowImbalance(final double bidAdded, final double bidRemoved,
                                            final double askAdded, final double askRemoved) {
        double bid = bidAdded - bidRemoved;
        double ask = askAdded - askRemoved;
        double denom = Math.abs(bidAdded) + Math.abs(bidRemoved) + Math.abs(askAdded) + Math.abs(askRemoved);
        return denom <= 0 ? Double.NaN : (bid - ask) / denom;
    }

    /** VPIN proxy: {@code |buy − sell| / (buy + sell)} ∈ [0, 1] over a volume window. IEX-slice approximation. */
    public static double vpin(final double buyVolume, final double sellVolume) {
        double total = buyVolume + sellVolume;
        return total <= 0 ? Double.NaN : Math.abs(buyVolume - sellVolume) / total;
    }

    /**
     * Kyle's λ: OLS slope of price change on signed volume (price impact per
     * unit of one-sided flow). IEX-slice approximation. NaN if the inputs are
     * mismatched, &lt;3 points, or signed volume has zero variance.
     */
    public static double kylesLambda(final double[] signedVolume, final double[] priceChange) {
        if (signedVolume == null || priceChange == null
                || signedVolume.length != priceChange.length || signedVolume.length < 3) return Double.NaN;
        int n = signedVolume.length;
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) { mx += signedVolume[i]; my += priceChange[i]; }
        mx /= n; my /= n;
        double sxy = 0, sxx = 0;
        for (int i = 0; i < n; i++) { double dx = signedVolume[i] - mx; sxy += dx * (priceChange[i] - my); sxx += dx * dx; }
        return sxx == 0 ? Double.NaN : sxy / sxx;
    }
}
