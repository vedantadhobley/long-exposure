package com.longexposure.wire;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Boundary tests for the tiered round-lot rule. */
class RoundLotTest {

    @Test
    void tier1Under250() {
        assertEquals(100L, RoundLot.forPriceRaw(1L));               // $0.0001
        assertEquals(100L, RoundLot.forPriceRaw(99_05_00L));         // $99.0500
        assertEquals(100L, RoundLot.forPriceRaw(250_00_00L));        // $250.0000 (inclusive)
    }

    @Test
    void tier2Between250And1000() {
        assertEquals(40L, RoundLot.forPriceRaw(250_00_01L));         // $250.0001 (first cent above)
        assertEquals(40L, RoundLot.forPriceRaw(502_11_00L));         // SOXX $502.11
        assertEquals(40L, RoundLot.forPriceRaw(520_00_00L));         // CRWD $520.00
        assertEquals(40L, RoundLot.forPriceRaw(1_000_00_00L));       // $1,000.00 (inclusive)
    }

    @Test
    void tier3Between1000And10000() {
        assertEquals(10L, RoundLot.forPriceRaw(1_000_00_01L));
        assertEquals(10L, RoundLot.forPriceRaw(7_025_06_00L));       // GME stub $7,025.06
        assertEquals(10L, RoundLot.forPriceRaw(10_000_00_00L));
    }

    @Test
    void tier4Above10000() {
        assertEquals(1L, RoundLot.forPriceRaw(10_000_00_01L));
        assertEquals(1L, RoundLot.forPriceRaw(1_000_000_00_00L));    // BRK.A territory
    }
}
