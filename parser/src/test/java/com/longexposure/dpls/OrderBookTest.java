package com.longexposure.dpls;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State-machine correctness tests for {@link OrderBook}. Synthetic message
 * streams; tens of events per test. All construction uses the public record
 * canonical constructors so we exercise the actual decoded-message shape.
 */
class OrderBookTest {

    private static final String SYM = "ZIEXT";
    private static final long T0 = 1_700_000_000_000_000_000L; // arbitrary base ns

    // ─── empty book ──────────────────────────────────────────────────────────

    @Test
    void emptyBookHasNoBboNoOrders() {
        OrderBook b = new OrderBook(SYM);
        assertTrue(b.isEmpty());
        assertEquals(0, b.orderCount());
        assertEquals(OptionalLong.empty(), b.bestBidPriceRaw());
        assertEquals(OptionalLong.empty(), b.bestAskPriceRaw());
        assertEquals(0L, b.sizeAtBestBid());
        assertEquals(0L, b.sizeAtBestAsk());
        assertEquals(0L, b.totalBidSize());
        assertEquals(0L, b.totalAskSize());
        assertEquals(0, b.bidLevelCount());
        assertEquals(0, b.askLevelCount());
        assertFalse(b.contains(123));
        assertNull(b.get(123));
    }

    // ─── add order ───────────────────────────────────────────────────────────

    @Test
    void addOrderPopulatesBookAndBbo() {
        OrderBook b = new OrderBook(SYM);
        b.apply(buy(1, 100, 990500L, T0));   // 100 @ $99.05

        assertEquals(1, b.orderCount());
        assertTrue(b.contains(1L));
        assertEquals(OptionalLong.of(990500L), b.bestBidPriceRaw());
        assertEquals(100L, b.sizeAtBestBid());
        assertEquals(100L, b.totalBidSize());
        assertEquals(0L, b.totalAskSize());
        assertEquals(OptionalLong.empty(), b.bestAskPriceRaw());

        OrderState s = b.get(1L);
        assertEquals(AddOrder.Side.BUY, s.side());
        assertEquals(990500L, s.priceRaw());
        assertEquals(100, s.size());
        assertEquals(T0, s.addedNanos());
        assertEquals(T0, s.priorityNanos());
    }

    @Test
    void multipleAddsAtSamePriceAggregate() {
        OrderBook b = new OrderBook(SYM);
        b.apply(buy(1, 100, 990500L, T0));
        b.apply(buy(2, 200, 990500L, T0 + 1));
        b.apply(buy(3, 50,  990500L, T0 + 2));

        assertEquals(3, b.orderCount());
        assertEquals(1, b.bidLevelCount());                       // single price level
        assertEquals(350L, b.sizeAtBestBid());                    // 100 + 200 + 50
        assertEquals(350L, b.totalBidSize());
    }

    @Test
    void duplicateAddOrderIdThrows() {
        OrderBook b = new OrderBook(SYM);
        b.apply(buy(1, 100, 990500L, T0));
        assertThrows(IllegalStateException.class,
                () -> b.apply(buy(1, 200, 990600L, T0 + 1)));
    }

    // ─── execute ─────────────────────────────────────────────────────────────

    @Test
    void partialExecuteDecrementsSize() {
        OrderBook b = new OrderBook(SYM);
        b.apply(buy(1, 100, 990500L, T0));
        b.apply(execute(1, 30, 990500L, 999L, T0 + 1));   // 30 of 100 filled

        assertEquals(1, b.orderCount());
        assertEquals(70, b.get(1L).size());
        assertEquals(70L, b.sizeAtBestBid());
    }

    @Test
    void fullExecuteRemovesOrder() {
        OrderBook b = new OrderBook(SYM);
        b.apply(buy(1, 100, 990500L, T0));
        b.apply(execute(1, 100, 990500L, 999L, T0 + 1));

        assertTrue(b.isEmpty());
        assertFalse(b.contains(1L));
        assertEquals(OptionalLong.empty(), b.bestBidPriceRaw());
    }

    @Test
    void overExecuteThrows() {
        OrderBook b = new OrderBook(SYM);
        b.apply(buy(1, 100, 990500L, T0));
        assertThrows(IllegalStateException.class,
                () -> b.apply(execute(1, 101, 990500L, 999L, T0 + 1)));
    }

    @Test
    void executeUnknownOrderIdThrows() {
        OrderBook b = new OrderBook(SYM);
        assertThrows(IllegalStateException.class,
                () -> b.apply(execute(42, 10, 990500L, 999L, T0)));
    }

    // ─── delete ──────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesOrderAndClearsLevelWhenLast() {
        OrderBook b = new OrderBook(SYM);
        b.apply(buy(1, 100, 990500L, T0));
        b.apply(new OrderDelete(T0 + 1, SYM, 1L));

        assertTrue(b.isEmpty());
        assertEquals(0, b.bidLevelCount());
    }

    @Test
    void deleteLeavesOtherOrdersAtSameLevel() {
        OrderBook b = new OrderBook(SYM);
        b.apply(buy(1, 100, 990500L, T0));
        b.apply(buy(2, 200, 990500L, T0 + 1));
        b.apply(new OrderDelete(T0 + 2, SYM, 1L));

        assertEquals(1, b.orderCount());
        assertEquals(200L, b.sizeAtBestBid());                    // only order 2 left
    }

    @Test
    void deleteUnknownOrderIdThrows() {
        OrderBook b = new OrderBook(SYM);
        assertThrows(IllegalStateException.class,
                () -> b.apply(new OrderDelete(T0, SYM, 42L)));
    }

    @Test
    void addAfterDeleteWithSameIdIsFreshOrder() {
        OrderBook b = new OrderBook(SYM);
        b.apply(buy(1, 100, 990500L, T0));
        b.apply(new OrderDelete(T0 + 1, SYM, 1L));
        b.apply(buy(1, 50, 990600L, T0 + 2));   // same orderId reused after delete

        assertEquals(1, b.orderCount());
        assertEquals(OptionalLong.of(990600L), b.bestBidPriceRaw());
        assertEquals(T0 + 2, b.get(1L).addedNanos());             // fresh add timestamp
    }

    // ─── modify ──────────────────────────────────────────────────────────────

    @Test
    void modifyMaintainPriorityKeepsPriorityNanos() {
        OrderBook b = new OrderBook(SYM);
        b.apply(buy(1, 100, 990500L, T0));
        b.apply(modify(1, 60, 990500L, true, T0 + 1));     // size-reduce, maintain priority

        OrderState s = b.get(1L);
        assertEquals(60, s.size());
        assertEquals(T0, s.priorityNanos());                      // unchanged
        assertEquals(T0, s.addedNanos());
        assertEquals(60L, b.sizeAtBestBid());
    }

    @Test
    void modifyResetPriorityUpdatesPriorityNanos() {
        OrderBook b = new OrderBook(SYM);
        b.apply(buy(1, 100, 990500L, T0));
        b.apply(modify(1, 100, 990600L, false, T0 + 1));   // price-change, reset priority

        OrderState s = b.get(1L);
        assertEquals(990600L, s.priceRaw());
        assertEquals(T0 + 1, s.priorityNanos());                  // reset to modify ts
        assertEquals(T0, s.addedNanos());                         // original add ts kept
    }

    @Test
    void modifyAcrossPriceLevelsReshufflesLevels() {
        OrderBook b = new OrderBook(SYM);
        b.apply(buy(1, 100, 990500L, T0));
        b.apply(buy(2, 200, 990500L, T0 + 1));
        b.apply(modify(1, 100, 990400L, false, T0 + 2));   // move order 1 down to $99.04

        assertEquals(2, b.bidLevelCount());                       // two distinct levels now
        assertEquals(990500L, b.bestBidPriceRaw().getAsLong());
        assertEquals(200L, b.sizeAtBestBid());                    // only order 2 at the top
        assertEquals(100L, b.sizeAtBidLevel(2));                  // order 1 at level 2
    }

    @Test
    void modifyUnknownOrderIdThrows() {
        OrderBook b = new OrderBook(SYM);
        assertThrows(IllegalStateException.class,
                () -> b.apply(modify(42, 100, 990500L, true, T0)));
    }

    // ─── clear ───────────────────────────────────────────────────────────────

    @Test
    void clearEmptiesBook() {
        OrderBook b = new OrderBook(SYM);
        b.apply(buy(1, 100, 990500L, T0));
        b.apply(sell(2, 50, 990700L, T0 + 1));
        b.clear();

        assertTrue(b.isEmpty());
        assertEquals(0, b.bidLevelCount());
        assertEquals(0, b.askLevelCount());
    }

    // ─── BBO with two-sided book ─────────────────────────────────────────────

    @Test
    void bboFromTwoSidedBook() {
        OrderBook b = new OrderBook(SYM);
        // Bids: $99.00 (size 50), $99.05 (size 100 + 200)
        b.apply(buy(1, 50,  990000L, T0));
        b.apply(buy(2, 100, 990500L, T0 + 1));
        b.apply(buy(3, 200, 990500L, T0 + 2));
        // Asks: $99.07 (size 75), $99.10 (size 150)
        b.apply(sell(4, 75,  990700L, T0 + 3));
        b.apply(sell(5, 150, 991000L, T0 + 4));

        assertEquals(990500L, b.bestBidPriceRaw().getAsLong());
        assertEquals(300L,    b.sizeAtBestBid());                 // aggregated
        assertEquals(990700L, b.bestAskPriceRaw().getAsLong());
        assertEquals(75L,     b.sizeAtBestAsk());
        assertEquals(350L,    b.totalBidSize());
        assertEquals(225L,    b.totalAskSize());
        assertEquals(2, b.bidLevelCount());
        assertEquals(2, b.askLevelCount());

        // depth
        assertEquals(300L, b.sizeAtBidLevel(1));                  // best bid level
        assertEquals(50L,  b.sizeAtBidLevel(2));                  // next-best bid
        assertEquals(75L,  b.sizeAtAskLevel(1));
        assertEquals(150L, b.sizeAtAskLevel(2));
        assertEquals(0L,   b.sizeAtBidLevel(3));                  // beyond book
    }

    @Test
    void sequenceOfModifiesAndExecutesMaintainsLevelInvariants() {
        OrderBook b = new OrderBook(SYM);
        b.apply(buy(1, 100, 990500L, T0));
        b.apply(buy(2, 200, 990500L, T0 + 1));
        b.apply(buy(3, 50,  990400L, T0 + 2));

        // Total bid: 350 (300 at top, 50 at level 2)
        assertEquals(350L, b.totalBidSize());

        // Modify order 2 to halve its size (same price, maintain priority)
        b.apply(modify(2, 100, 990500L, true, T0 + 3));
        assertEquals(250L, b.totalBidSize());                     // 100 + 100 + 50
        assertEquals(200L, b.sizeAtBestBid());

        // Partial-execute order 1 (60 of 100)
        b.apply(execute(1, 60, 990500L, 5001L, T0 + 4));
        assertEquals(190L, b.totalBidSize());                     // 40 + 100 + 50
        assertEquals(140L, b.sizeAtBestBid());

        // Delete order 3 (was at level 2)
        b.apply(new OrderDelete(T0 + 5, SYM, 3L));
        assertEquals(140L, b.totalBidSize());                     // 40 + 100
        assertEquals(1, b.bidLevelCount());                       // only $99.05 left
    }

    // ─── round-lot-protected BBO ─────────────────────────────────────────────
    //
    // Mirrors TOPS QuoteUpdate semantics: a price level only qualifies as
    // the best when its aggregate displayed size meets the round-lot
    // threshold (100 shares for most NMS equities). Without this rule, an
    // odd-lot order resting above the round-lot top produces a derived BBO
    // that disagrees with TOPS — see the 2026-05-11 BBO-cross-validation
    // investigation that surfaced the spec gap.

    @Test
    void roundLotSkipsOddLotOnlyLevel() {
        OrderBook b = new OrderBook(SYM);
        b.apply(buy(1, 1,   729000L, T0));       // odd lot at $72.90
        b.apply(buy(2, 500, 728800L, T0 + 1));   // round lot at $72.88

        // Unprotected: odd lot wins
        assertEquals(OptionalLong.of(729000L), b.bestBidPriceRaw());
        assertEquals(1L, b.sizeAtBestBid());

        // Round-lot-protected: skip the 1-share level, $72.88 qualifies
        assertEquals(OptionalLong.of(728800L), b.bestBidPriceRaw(100L));
        assertEquals(500L, b.sizeAtBestBid(100L));
    }

    @Test
    void roundLotAggregatesOddLotAtQualifyingPrice() {
        OrderBook b = new OrderBook(SYM);
        b.apply(buy(1, 1,   729000L, T0));       // odd lot at $72.90
        b.apply(buy(2, 500, 729000L, T0 + 1));   // round lot at same price → aggregate = 501

        // 501 ≥ 100 so $72.90 qualifies; full aggregate reported (matches TOPS)
        assertEquals(OptionalLong.of(729000L), b.bestBidPriceRaw(100L));
        assertEquals(501L, b.sizeAtBestBid(100L));
    }

    @Test
    void roundLotReturnsEmptyWhenNoLevelQualifies() {
        OrderBook b = new OrderBook(SYM);
        b.apply(buy(1, 1,  729000L, T0));        // odd lot
        b.apply(buy(2, 50, 728900L, T0 + 1));    // odd lot
        b.apply(buy(3, 75, 728800L, T0 + 2));    // odd lot — none reach 100

        assertEquals(OptionalLong.empty(), b.bestBidPriceRaw(100L));
        assertEquals(0L, b.sizeAtBestBid(100L));

        // And the ask side, symmetrically
        b.apply(sell(4, 40, 422400L, T0 + 3));
        assertEquals(OptionalLong.empty(), b.bestAskPriceRaw(100L));
        assertEquals(0L, b.sizeAtBestAsk(100L));
    }

    @Test
    void roundLotSkipsMultipleOddLotLevelsToFindQualifying() {
        // Mirrors the GME-style mismatch: a "stub quote" deep below where
        // multiple odd-lot levels sit above the next round-lot-qualifying
        // price.
        OrderBook b = new OrderBook(SYM);
        b.apply(sell(1, 40,  4_224_000L, T0));        // 40 @ $422.40
        b.apply(sell(2, 25,  5_000_000L, T0 + 1));    // 25 @ $500.00
        b.apply(sell(3, 125, 70_250_600L, T0 + 2));   // 125 @ $7025.06 (qualifies)

        // Unprotected sees $422.40 (40 shares)
        assertEquals(OptionalLong.of(4_224_000L), b.bestAskPriceRaw());

        // Round-lot-protected jumps to $7025.06 (the GME pattern)
        assertEquals(OptionalLong.of(70_250_600L), b.bestAskPriceRaw(100L));
        assertEquals(125L, b.sizeAtBestAsk(100L));
    }

    @Test
    void roundLotEqualToThresholdQualifies() {
        // Boundary: exactly 100 shares should qualify.
        OrderBook b = new OrderBook(SYM);
        b.apply(buy(1, 100, 729000L, T0));
        assertEquals(OptionalLong.of(729000L), b.bestBidPriceRaw(100L));
        assertEquals(100L, b.sizeAtBestBid(100L));
    }

    @Test
    void roundLotWithMinSizeOneEqualsUnprotected() {
        // Defensive: a minSize of 1 should reproduce the original behavior.
        OrderBook b = new OrderBook(SYM);
        b.apply(buy(1, 1, 729000L, T0));
        assertEquals(b.bestBidPriceRaw(), b.bestBidPriceRaw(1L));
        assertEquals(b.sizeAtBestBid(), b.sizeAtBestBid(1L));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static AddOrder buy(final long orderId, final int size, final long priceRaw, final long ts) {
        return new AddOrder(AddOrder.Side.BUY, ts, SYM, orderId, size, priceRaw);
    }

    private static AddOrder sell(final long orderId, final int size, final long priceRaw, final long ts) {
        return new AddOrder(AddOrder.Side.SELL, ts, SYM, orderId, size, priceRaw);
    }

    private static OrderModify modify(final long orderId, final int newSize, final long newPriceRaw,
                                      final boolean maintainPriority, final long ts) {
        // Modify Flags bit 0 = 1 means MAINTAIN priority
        byte flags = (byte) (maintainPriority ? 0x01 : 0x00);
        return new OrderModify(flags, ts, SYM, orderId, newSize, newPriceRaw);
    }

    private static OrderExecuted execute(final long orderId, final int size, final long priceRaw,
                                         final long tradeId, final long ts) {
        return new OrderExecuted((byte) 0x00, ts, SYM, orderId, size, priceRaw, tradeId);
    }
}
