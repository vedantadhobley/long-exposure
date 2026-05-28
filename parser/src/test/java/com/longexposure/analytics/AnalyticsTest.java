package com.longexposure.analytics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void realizedVolatility_logReturns() {
        double r = Math.log(1.1);                                  // 100 -> 110 -> 100
        assertEquals(Math.sqrt(2 * r * r), Analytics.realizedVolatility(new double[]{100, 110, 100}), 1e-9);
        assertTrue(Double.isNaN(Analytics.realizedVolatility(new double[]{100})));
    }

    @Test
    void jumpRatio_continuousVsJump() {
        assertEquals(0.0, Analytics.jumpRatio(new double[]{2, 2, 2, 2, 2}), EPS); // equal returns -> clamped 0
        assertEquals(1.0, Analytics.jumpRatio(new double[]{0, 0, 5, 0, 0}), EPS); // a single jump
        assertTrue(Double.isNaN(Analytics.jumpRatio(new double[]{1, 1})));
    }

    @Test
    void hhi_concentration() {
        assertEquals(0.25, Analytics.hhi(new double[]{1, 1, 1, 1}), EPS);  // even
        assertEquals(1.0, Analytics.hhi(new double[]{10, 0, 0}), EPS);     // all in one
        assertTrue(Double.isNaN(Analytics.hhi(new double[]{})));
    }

    @Test
    void normalizedEntropy_uniformVsSkewed() {
        assertEquals(1.0, Analytics.normalizedEntropy(new double[]{1, 1, 1, 1}), EPS); // uniform
        assertEquals(1.0, Analytics.normalizedEntropy(new double[]{5, 5}), EPS);
        assertTrue(Analytics.normalizedEntropy(new double[]{3, 1}) < 1.0);             // skewed
        assertTrue(Double.isNaN(Analytics.normalizedEntropy(new double[]{4})));        // k<2
    }

    @Test
    void lag1Autocorrelation_trendVsAlternating() {
        assertEquals(0.4, Analytics.lag1Autocorrelation(new double[]{1, 2, 3, 4, 5}), 1e-9);
        assertEquals(-0.75, Analytics.lag1Autocorrelation(new double[]{1, -1, 1, -1}), 1e-9);
        assertTrue(Double.isNaN(Analytics.lag1Autocorrelation(new double[]{1, 2})));
    }

    @Test
    void cusumShift_stationaryVsShifted() {
        assertEquals(0.5, Analytics.cusumShift(new double[]{0, 0, 0, 5, 5, 5}), 1e-9);
        assertTrue(Analytics.cusumShift(new double[]{0, 0, 0, 5, 5, 5})
                 > Analytics.cusumShift(new double[]{1, 2, 1, 2, 1, 2}));
        assertTrue(Double.isNaN(Analytics.cusumShift(new double[]{1, 1, 1})));  // zero variance
    }

    @Test
    void branchingRatioFromFano_momentEstimate() {
        assertEquals(0.5, Analytics.branchingRatioFromFano(4.0), EPS);    // 1 - 1/2
        assertTrue(Double.isNaN(Analytics.branchingRatioFromFano(1.0)));  // no excitation
        assertTrue(Double.isNaN(Analytics.branchingRatioFromFano(0.5)));
    }

    @Test
    void orderFlowImbalance_signedFlow() {
        assertEquals(1.0, Analytics.orderFlowImbalance(100, 0, 0, 0), EPS);  // all bid accumulation
        assertEquals(0.0, Analytics.orderFlowImbalance(50, 0, 50, 0), EPS);  // balanced
        assertTrue(Double.isNaN(Analytics.orderFlowImbalance(0, 0, 0, 0)));
    }

    @Test
    void vpin_imbalanceFraction() {
        assertEquals(0.6, Analytics.vpin(80, 20), EPS);
        assertTrue(Double.isNaN(Analytics.vpin(0, 0)));
    }

    @Test
    void kylesLambda_impactSlope() {
        assertEquals(2.0, Analytics.kylesLambda(new double[]{1, 2, 3}, new double[]{2, 4, 6}), 1e-9);
        assertTrue(Double.isNaN(Analytics.kylesLambda(new double[]{1, 2}, new double[]{1, 2, 3})));   // mismatched
        assertTrue(Double.isNaN(Analytics.kylesLambda(new double[]{1, 1, 1}, new double[]{1, 2, 3}))); // zero var
    }

    // ─── Class-label helpers (anchored vocabulary for prose; added 2026-05-28) ──

    @Test
    void fanoClass_bands() {
        assertEquals("highly bursty",     Analytics.fanoClass(10.0));
        assertEquals("highly bursty",     Analytics.fanoClass(5.01));
        assertEquals("moderately bursty", Analytics.fanoClass(3.0));
        assertEquals("moderately bursty", Analytics.fanoClass(2.01));
        assertEquals("weakly bursty",     Analytics.fanoClass(1.5));
        assertEquals("weakly bursty",     Analytics.fanoClass(1.01));
        assertEquals("Poisson-like",      Analytics.fanoClass(1.0));        // exactly 1 = Poisson
        assertEquals("Poisson-like",      Analytics.fanoClass(0.5));        // underdispersed
        assertEquals("Poisson-like",      Analytics.fanoClass(0.0));
        assertNull(Analytics.fanoClass(Double.NaN));
    }

    @Test
    void ofiClass_directionAndBalanced() {
        assertEquals("buyer-leaning",  Analytics.ofiClass(0.5));
        assertEquals("buyer-leaning",  Analytics.ofiClass(0.11));
        assertEquals("balanced",       Analytics.ofiClass(0.10));            // boundary: not > 0.1
        assertEquals("balanced",       Analytics.ofiClass(0.0));
        assertEquals("balanced",       Analytics.ofiClass(-0.10));           // boundary: not < -0.1
        assertEquals("seller-leaning", Analytics.ofiClass(-0.11));
        assertEquals("seller-leaning", Analytics.ofiClass(-1.0));
        assertNull(Analytics.ofiClass(Double.NaN));
    }

    @Test
    void depthImbalanceClass_sideAndBalanced() {
        assertEquals("bid-skewed", Analytics.depthImbalanceClass(0.4));
        assertEquals("bid-skewed", Analytics.depthImbalanceClass(0.11));
        assertEquals("balanced",   Analytics.depthImbalanceClass(0.10));
        assertEquals("balanced",   Analytics.depthImbalanceClass(0.0));
        assertEquals("balanced",   Analytics.depthImbalanceClass(-0.10));
        assertEquals("ask-skewed", Analytics.depthImbalanceClass(-0.11));
        assertEquals("ask-skewed", Analytics.depthImbalanceClass(-0.9));
        assertNull(Analytics.depthImbalanceClass(Double.NaN));
    }

    @Test
    void refillCadenceClass_bands() {
        assertEquals("metronomic", Analytics.refillCadenceClass(0.0));      // identical gaps
        assertEquals("metronomic", Analytics.refillCadenceClass(0.29));
        assertEquals("regular",    Analytics.refillCadenceClass(0.3));      // boundary: not < 0.3
        assertEquals("regular",    Analytics.refillCadenceClass(0.99));
        assertEquals("irregular",  Analytics.refillCadenceClass(1.0));      // boundary
        assertEquals("irregular",  Analytics.refillCadenceClass(1.99));
        assertEquals("erratic",    Analytics.refillCadenceClass(2.0));      // boundary: not < 2.0
        assertEquals("erratic",    Analytics.refillCadenceClass(5.0));
        assertNull(Analytics.refillCadenceClass(Double.NaN));
    }
}
