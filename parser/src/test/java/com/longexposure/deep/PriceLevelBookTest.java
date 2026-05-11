package com.longexposure.deep;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State-machine tests for {@link PriceLevelBook}. Mirrors the structure
 * of {@code OrderBookTest} in the DPLS package; the DEEP book is much
 * simpler since there are no orderIds or lifecycle states — every PLU is
 * a direct write at (side, price).
 */
class PriceLevelBookTest {

    private static final String SYM = "ZIEXT";
    private static final long T0 = 1_700_000_000_000_000_000L;

    @Test
    void emptyBookHasNoBboNoLevels() {
        PriceLevelBook b = new PriceLevelBook(SYM);
        assertTrue(b.isEmpty());
        assertEquals(0, b.bidLevelCount());
        assertEquals(0, b.askLevelCount());
        assertEquals(OptionalLong.empty(), b.bestBidPriceRaw());
        assertEquals(OptionalLong.empty(), b.bestAskPriceRaw());
        assertEquals(0L, b.sizeAtBestBid());
        assertEquals(0L, b.sizeAtBestAsk());
    }

    @Test
    void appliedPluPopulatesBidLevel() {
        PriceLevelBook b = new PriceLevelBook(SYM);
        b.apply(buyPlu(990500L, 500, T0));            // 500 shares @ $99.05

        assertEquals(1, b.bidLevelCount());
        assertEquals(0, b.askLevelCount());
        assertEquals(OptionalLong.of(990500L), b.bestBidPriceRaw());
        assertEquals(500L, b.sizeAtBestBid());
    }

    @Test
    void secondPluSamePriceReplacesNotAdds() {
        // DEEP semantics: PLU is the new AGGREGATE at this (side, price),
        // not a delta. This is the most important behavior to lock down.
        PriceLevelBook b = new PriceLevelBook(SYM);
        b.apply(buyPlu(990500L, 500, T0));
        b.apply(buyPlu(990500L, 300, T0 + 1));        // replaces 500 with 300

        assertEquals(1, b.bidLevelCount());
        assertEquals(300L, b.sizeAtBestBid());        // not 800
    }

    @Test
    void sizeZeroRemovesLevel() {
        PriceLevelBook b = new PriceLevelBook(SYM);
        b.apply(buyPlu(990500L, 500, T0));
        b.apply(buyPlu(990500L, 0, T0 + 1));          // level removed

        assertEquals(0, b.bidLevelCount());
        assertTrue(b.bestBidPriceRaw().isEmpty());
    }

    @Test
    void multipleLevelsOrderedByPrice() {
        PriceLevelBook b = new PriceLevelBook(SYM);
        b.apply(buyPlu(989500L, 400, T0));            // $98.95
        b.apply(buyPlu(990500L, 500, T0 + 1));        // $99.05 (better)
        b.apply(buyPlu(988500L, 300, T0 + 2));        // $98.85 (worse)

        assertEquals(3, b.bidLevelCount());
        assertEquals(OptionalLong.of(990500L), b.bestBidPriceRaw());
        assertEquals(500L, b.sizeAtBestBid());
    }

    @Test
    void bidAndAskKeptIndependent() {
        PriceLevelBook b = new PriceLevelBook(SYM);
        b.apply(buyPlu(990500L, 500, T0));            // bid $99.05
        b.apply(sellPlu(990800L, 200, T0 + 1));       // ask $99.08

        assertEquals(1, b.bidLevelCount());
        assertEquals(1, b.askLevelCount());
        assertEquals(990500L, b.bestBidPriceRaw().orElseThrow());
        assertEquals(990800L, b.bestAskPriceRaw().orElseThrow());
        assertEquals(500L, b.sizeAtBestBid());
        assertEquals(200L, b.sizeAtBestAsk());
    }

    @Test
    void roundLotProtectionSkipsOddLotOnlyLevel() {
        // DEEP analogue of the TOPS-mismatch pattern: a 50-share level
        // shouldn't qualify as the best bid; walk down to the next
        // round-lot-qualifying level.
        PriceLevelBook b = new PriceLevelBook(SYM);
        b.apply(buyPlu(990500L, 50,  T0));            // odd lot
        b.apply(buyPlu(989500L, 500, T0 + 1));        // round lot

        assertEquals(OptionalLong.of(989500L), b.bestBidPriceRaw(100L));
        assertEquals(500L, b.sizeAtBestBid(100L));
        // Unprotected still sees the odd lot
        assertEquals(OptionalLong.of(990500L), b.bestBidPriceRaw());
        assertEquals(50L, b.sizeAtBestBid());
    }

    @Test
    void applyClearDropsBothSides() {
        PriceLevelBook b = new PriceLevelBook(SYM);
        b.apply(buyPlu(990500L, 500, T0));
        b.apply(sellPlu(990800L, 200, T0 + 1));
        b.applyClear();

        assertTrue(b.isEmpty());
        assertFalse(b.bestBidPriceRaw().isPresent());
        assertFalse(b.bestAskPriceRaw().isPresent());
    }

    private static PriceLevelUpdate buyPlu(final long priceRaw, final int size, final long ts) {
        return new PriceLevelUpdate(PriceLevelUpdate.Side.BUY, (byte) 0x01, ts, SYM, size, priceRaw);
    }

    private static PriceLevelUpdate sellPlu(final long priceRaw, final int size, final long ts) {
        return new PriceLevelUpdate(PriceLevelUpdate.Side.SELL, (byte) 0x01, ts, SYM, size, priceRaw);
    }
}
