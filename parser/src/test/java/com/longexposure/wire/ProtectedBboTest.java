package com.longexposure.wire;

import org.junit.jupiter.api.Test;

import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the round-lot-protected BBO. Each case is named after the
 * 2026-05-08 real-data pattern it reproduces so a future reader can map
 * test → trace.
 */
class ProtectedBboTest {

    @Test
    void emptyBookProducesEmptyBbo() {
        assertFalse(ProtectedBbo.from(new TreeMap<Long, Long>()).isPresent());
    }

    @Test
    void singleRoundLotLevelQualifies() {
        TreeMap<Long, Long> bids = new TreeMap<>();
        bids.put(99_05_00L, 500L);                                       // $99.05 × 500
        ProtectedBbo bbo = ProtectedBbo.from(bids.descendingMap());
        assertTrue(bbo.isPresent());
        assertEquals(99_05_00L, bbo.priceRaw());
        assertEquals(500L, bbo.size());
    }

    @Test
    void oddLotOnlyLevelDoesNotQualifyAndYieldsEmpty() {
        // TQQQ phantom-bid pattern: a stray 1-share order at $72.90.
        // TOPS reports 0/0; our BBO must too.
        TreeMap<Long, Long> bids = new TreeMap<>();
        bids.put(72_90_00L, 1L);
        ProtectedBbo bbo = ProtectedBbo.from(bids.descendingMap());
        assertFalse(bbo.isPresent());
    }

    @Test
    void oddLotAboveAggregatesIntoQualifyingLevelSize_smrPattern() {
        // SMR: 75 shares @ $12.45 (odd) above 100 shares @ $12.44 (round).
        // TOPS BBO: 175 @ $12.44. Both rules together produce that.
        TreeMap<Long, Long> bids = new TreeMap<>();
        bids.put(12_45_00L, 75L);
        bids.put(12_44_00L, 100L);
        ProtectedBbo bbo = ProtectedBbo.from(bids.descendingMap());
        assertEquals(12_44_00L, bbo.priceRaw());
        assertEquals(175L, bbo.size());
    }

    @Test
    void oddLotAboveAggregatesIntoQualifyingAskLevelSize_maskPattern() {
        // MASK ask: 47 shares @ $2.86 (odd) above 100 shares @ $2.89 (round).
        // For asks the "better" side is lower prices, ascending iteration.
        TreeMap<Long, Long> asks = new TreeMap<>();
        asks.put(2_86_00L, 47L);
        asks.put(2_89_00L, 100L);
        ProtectedBbo bbo = ProtectedBbo.from(asks);
        assertEquals(2_89_00L, bbo.priceRaw());
        assertEquals(147L, bbo.size());
    }

    @Test
    void tier2SymbolHigherPriceLowerThreshold_soxxPattern() {
        // SOXX ask $502.11 × 80 shares. Tier 2 → round lot 40.
        // 80 ≥ 40 so this level qualifies on its own.
        TreeMap<Long, Long> asks = new TreeMap<>();
        asks.put(502_11_00L, 80L);
        ProtectedBbo bbo = ProtectedBbo.from(asks);
        assertEquals(502_11_00L, bbo.priceRaw());
        assertEquals(80L, bbo.size());
    }

    @Test
    void tier3SymbolStubQuote_gmePattern() {
        // GME stub ask: 125 @ $7,025.06. Tier 3 → round lot 10. Qualifies.
        TreeMap<Long, Long> asks = new TreeMap<>();
        asks.put(7_025_06_00L, 125L);
        ProtectedBbo bbo = ProtectedBbo.from(asks);
        assertEquals(7_025_06_00L, bbo.priceRaw());
        assertEquals(125L, bbo.size());
    }

    @Test
    void odd_LotsStackAcrossMultipleLevelsBeforeQualifying() {
        // Multiple odd-lot levels above the round-lot one — all aggregated.
        TreeMap<Long, Long> bids = new TreeMap<>();
        bids.put(50_03_00L, 30L);                                        // odd
        bids.put(50_02_00L, 25L);                                        // odd
        bids.put(50_01_00L, 40L);                                        // odd
        bids.put(50_00_00L, 100L);                                       // qualifies (tier 1)
        ProtectedBbo bbo = ProtectedBbo.from(bids.descendingMap());
        assertEquals(50_00_00L, bbo.priceRaw());
        assertEquals(30L + 25L + 40L + 100L, bbo.size());                // 195
    }

    @Test
    void cumulativeQualifies_aiioPattern() {
        // AIIO 2026-05-08 12:24:38 ask side: $1.13×18, $12.50×1, $67.45×90.
        // No single level meets the 100-share tier-1 threshold, but the
        // running cumulative crosses 100 at $67.45 — TOPS reports BBO
        // ask = $67.45 × 109. Cumulative rule must produce the same.
        TreeMap<Long, Long> asks = new TreeMap<>();
        asks.put(1_13_00L,  18L);
        asks.put(12_50_00L,  1L);
        asks.put(67_45_00L, 90L);
        ProtectedBbo bbo = ProtectedBbo.from(asks);
        assertEquals(67_45_00L, bbo.priceRaw());
        assertEquals(109L, bbo.size());
    }

    @Test
    void cumulativeBelowThresholdYieldsEmpty() {
        // Even cumulatively under the tier threshold — empty BBO.
        TreeMap<Long, Long> bids = new TreeMap<>();
        bids.put(50_00_00L, 30L);                                        // tier 1, needs 100
        bids.put(49_00_00L, 20L);
        bids.put(48_00_00L, 40L);                                        // cum=90 across all
        ProtectedBbo bbo = ProtectedBbo.from(bids.descendingMap());
        assertFalse(bbo.isPresent());
    }

    @Test
    void cumulativeReachesThresholdAtSubThresholdLevel() {
        // Three sub-threshold levels whose cumulative crosses 100 at the
        // bottom one — the bottom level qualifies via cumulative even
        // though its own size is well under 100. AIIO-shaped but in
        // a fully synthetic case.
        TreeMap<Long, Long> bids = new TreeMap<>();
        bids.put(50_00_00L, 30L);
        bids.put(49_00_00L, 50L);
        bids.put(48_00_00L, 80L);                                        // cum=160 at this level
        ProtectedBbo bbo = ProtectedBbo.from(bids.descendingMap());
        assertEquals(48_00_00L, bbo.priceRaw());
        assertEquals(160L, bbo.size());
    }
}
