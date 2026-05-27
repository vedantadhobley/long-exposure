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
}
