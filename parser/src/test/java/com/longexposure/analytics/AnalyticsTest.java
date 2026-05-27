package com.longexposure.analytics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure microstructure/statistical computations. */
class AnalyticsTest {

    private static final double EPS = 1e-9;

    @Test
    void slippageBps_walksUpAndDown() {
        // $100.0000 -> $100.1100 = +11 bps
        assertEquals(11.0, Analytics.slippageBps(1_000_000L, 1_001_100L), 1e-6);
        // walked down: $100.0000 -> $99.9000 = -10 bps
        assertEquals(-10.0, Analytics.slippageBps(1_000_000L, 999_000L), 1e-6);
        // no movement
        assertEquals(0.0, Analytics.slippageBps(1_000_000L, 1_000_000L), EPS);
        // degenerate first price -> 0, never a divide-by-zero
        assertEquals(0.0, Analytics.slippageBps(0L, 500_000L), EPS);
    }

    @Test
    void oneSidedness_classifiesAndRatios() {
        Analytics.OneSidedness buy = Analytics.oneSidedness(80, 20);
        assertEquals(0.8, buy.ratio(), EPS);
        assertEquals("buy", buy.dominant());

        Analytics.OneSidedness allSell = Analytics.oneSidedness(0, 50);
        assertEquals(1.0, allSell.ratio(), EPS);
        assertEquals("sell", allSell.dominant());

        Analytics.OneSidedness balanced = Analytics.oneSidedness(50, 50);
        assertEquals(0.5, balanced.ratio(), EPS);
        assertEquals("balanced", balanced.dominant());

        Analytics.OneSidedness empty = Analytics.oneSidedness(0, 0);
        assertEquals(0.0, empty.ratio(), EPS);
        assertEquals("none", empty.dominant());
    }

    @Test
    void pctOfBaseline_andGuard() {
        assertEquals(20.0, Analytics.pctOfBaseline(10_000L, 50_000.0), EPS);
        assertEquals(250.0, Analytics.pctOfBaseline(125_000L, 50_000.0), EPS);
        assertTrue(Double.isNaN(Analytics.pctOfBaseline(10_000L, 0.0)));
        assertTrue(Double.isNaN(Analytics.pctOfBaseline(10_000L, -1.0)));
    }

    @Test
    void median_oddEvenEmpty() {
        assertEquals(2.0, Analytics.median(new double[]{3, 1, 2}), EPS);   // unsorted ok
        assertEquals(2.5, Analytics.median(new double[]{1, 2, 3, 4}), EPS);
        assertTrue(Double.isNaN(Analytics.median(new double[]{})));
    }

    @Test
    void mad_medianOfAbsoluteDeviations() {
        // values [1..5], median 3, deviations [2,1,0,1,2], median deviation 1
        assertEquals(1.0, Analytics.mad(new double[]{1, 2, 3, 4, 5}), EPS);
    }

    @Test
    void robustZ_scaledByMad_andGuard() {
        // (10 - 4) / (1.4826 * 1) ≈ 4.047
        assertEquals(6.0 / 1.4826, Analytics.robustZ(10, 4, 1), 1e-6);
        // no dispersion -> 0, never a divide-by-zero
        assertEquals(0.0, Analytics.robustZ(10, 4, 0), EPS);
    }

    @Test
    void percentileRank_fractionBelow() {
        double[] window = {1, 2, 3, 4};
        assertEquals(100.0, Analytics.percentileRank(5, window), EPS);  // all below
        assertEquals(25.0, Analytics.percentileRank(2, window), EPS);   // one (1) below
        assertEquals(0.0, Analytics.percentileRank(1, window), EPS);    // none strictly below
        assertTrue(Double.isNaN(Analytics.percentileRank(5, new double[]{})));
    }

    @Test
    void coefficientOfVariation_dispersionAndGuards() {
        assertEquals(0.0, Analytics.coefficientOfVariation(new double[]{2, 2, 2}), EPS); // uniform
        // {1,2,3,4,5}: mean 3, sample sd sqrt(2.5)=1.58114, CV=0.527046
        assertEquals(0.5270463, Analytics.coefficientOfVariation(new double[]{1, 2, 3, 4, 5}), 1e-6);
        assertTrue(Double.isNaN(Analytics.coefficientOfVariation(new double[]{5})));      // n<2
        assertTrue(Double.isNaN(Analytics.coefficientOfVariation(new double[]{-1, 1})));  // mean 0
    }

    @Test
    void fanoFactor_burstyVsUniform() {
        // 10 events, 2 per bin across 5 bins -> counts all equal -> variance 0
        assertEquals(0.0, Analytics.fanoFactor(
                new long[]{5, 15, 25, 35, 45, 55, 65, 75, 85, 95}, 5), EPS);
        // 5 events crammed at the start + 1 far out -> clustered -> Fano > 1
        assertTrue(Analytics.fanoFactor(new long[]{0, 1, 2, 3, 4, 100}, 5) > 1.0);
        assertTrue(Double.isNaN(Analytics.fanoFactor(new long[]{5}, 5)));         // <2 events
        assertTrue(Double.isNaN(Analytics.fanoFactor(new long[]{5, 5, 5}, 5)));   // zero span
        assertTrue(Double.isNaN(Analytics.fanoFactor(new long[]{1, 2, 3}, 1)));   // bins<2
    }

    @Test
    void reversionFraction_transientPermanentOvershoot() {
        // moved 100 -> 110 (impact +10); came back to 102 -> 80% reverted
        assertEquals(0.8, Analytics.reversionFraction(100, 110, 102), EPS);
        assertEquals(0.0, Analytics.reversionFraction(100, 110, 110), EPS);  // no reversion (permanent)
        assertEquals(1.0, Analytics.reversionFraction(100, 110, 100), EPS);  // fully reverted (transient)
        assertEquals(-0.5, Analytics.reversionFraction(100, 110, 115), EPS); // continued in impact dir
        assertTrue(Double.isNaN(Analytics.reversionFraction(100, 100, 100))); // zero impact
    }

    @Test
    void effectiveSpreadBps_roundTripAndGuard() {
        // 5c off a $100 mid = 2 * 0.05/100 * 1e4 = 10 bps, both sides
        assertEquals(10.0, Analytics.effectiveSpreadBps(100.05, 100.00), 1e-6);
        assertEquals(10.0, Analytics.effectiveSpreadBps(99.95, 100.00), 1e-6);
        assertTrue(Double.isNaN(Analytics.effectiveSpreadBps(100.0, 0.0)));
    }

    @Test
    void orderToTradeRatio_spoofShapeAndGuards() {
        assertTrue(Double.isInfinite(Analytics.orderToTradeRatio(131, 0))); // posted, none filled
        assertEquals(25.0, Analytics.orderToTradeRatio(100, 4), EPS);
        assertTrue(Double.isNaN(Analytics.orderToTradeRatio(0, 5)));        // no orders
    }
}
