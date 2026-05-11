package com.longexposure.deepplus;

import com.longexposure.admin.SystemEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link OrderBookManager}: per-symbol routing, ClearBook
 * semantics, and the "ignore book-state-irrelevant message types" rule
 * (Trade, TradeBreak, all admin messages).
 */
class OrderBookManagerTest {

    private static final long T0 = 1_700_000_000_000_000_000L;

    @Test
    void routesAddOrderToCorrectSymbolBook() {
        OrderBookManager mgr = new OrderBookManager();
        mgr.apply(new AddOrder(AddOrder.Side.BUY, T0, "AAPL", 1L, 100, 1_900_000L));
        mgr.apply(new AddOrder(AddOrder.Side.SELL, T0 + 1, "GOOG", 2L, 50, 14_000_000L));

        assertEquals(2, mgr.symbolCount());
        assertEquals(2, mgr.totalOrderCount());
        assertNotNull(mgr.book("AAPL"));
        assertEquals(100L, mgr.book("AAPL").sizeAtBestBid());
        assertEquals(50L,  mgr.book("GOOG").sizeAtBestAsk());
        assertNull(mgr.book("MSFT"));
    }

    @Test
    void clearBookEmptiesOnlyThatSymbol() {
        OrderBookManager mgr = new OrderBookManager();
        mgr.apply(new AddOrder(AddOrder.Side.BUY, T0, "AAPL", 1L, 100, 1_900_000L));
        mgr.apply(new AddOrder(AddOrder.Side.BUY, T0 + 1, "GOOG", 2L, 200, 14_000_000L));
        mgr.apply(new ClearBook(T0 + 2, "AAPL"));

        assertTrue(mgr.book("AAPL").isEmpty());
        assertEquals(200L, mgr.book("GOOG").sizeAtBestBid());      // untouched
    }

    @Test
    void clearBookForUnknownSymbolIsNoOp() {
        OrderBookManager mgr = new OrderBookManager();
        mgr.apply(new ClearBook(T0, "NEVER_SEEN"));                // doesn't throw
        assertEquals(0, mgr.symbolCount());
    }

    @Test
    void tradeMessageDoesNotAffectBook() {
        OrderBookManager mgr = new OrderBookManager();
        mgr.apply(new AddOrder(AddOrder.Side.BUY, T0, "AAPL", 1L, 100, 1_900_000L));
        // Trade is non-displayed × non-displayed; per spec, doesn't touch the book
        mgr.apply(new Trade((byte) 0x00, T0 + 1, "AAPL", 50, 1_900_000L, 9001L));
        assertEquals(100L, mgr.book("AAPL").sizeAtBestBid());
        assertEquals(1, mgr.book("AAPL").orderCount());
    }

    @Test
    void tradeBreakMessageDoesNotAffectBook() {
        OrderBookManager mgr = new OrderBookManager();
        mgr.apply(new AddOrder(AddOrder.Side.BUY, T0, "AAPL", 1L, 100, 1_900_000L));
        mgr.apply(new TradeBreak((byte) 0x00, T0 + 1, "AAPL", 50, 1_900_000L, 9001L));
        assertEquals(100L, mgr.book("AAPL").sizeAtBestBid());
    }

    @Test
    void adminMessagesIgnored() {
        OrderBookManager mgr = new OrderBookManager();
        mgr.apply(new AddOrder(AddOrder.Side.BUY, T0, "AAPL", 1L, 100, 1_900_000L));
        // SystemEvent is admin; should be silently ignored
        mgr.apply(new SystemEvent(SystemEvent.Event.START_OF_REGULAR_MARKET_HOURS, T0 + 1));
        assertEquals(1, mgr.symbolCount());
        assertEquals(100L, mgr.book("AAPL").sizeAtBestBid());
    }

    @Test
    void fullLifecycleAcrossSymbols() {
        OrderBookManager mgr = new OrderBookManager();
        // AAPL: add, partial execute
        mgr.apply(new AddOrder(AddOrder.Side.BUY, T0, "AAPL", 1L, 100, 1_900_000L));
        mgr.apply(new OrderExecuted((byte) 0x00, T0 + 1, "AAPL", 1L, 40, 1_900_000L, 5001L));
        // GOOG: add, modify, delete
        mgr.apply(new AddOrder(AddOrder.Side.SELL, T0 + 2, "GOOG", 2L, 50, 14_000_000L));
        mgr.apply(new OrderModify((byte) 0x01, T0 + 3, "GOOG", 2L, 30, 14_000_000L));   // size-only, maintain
        mgr.apply(new OrderDelete(T0 + 4, "GOOG", 2L));

        assertEquals(60L, mgr.book("AAPL").sizeAtBestBid());
        assertTrue(mgr.book("GOOG").isEmpty());
        assertEquals(1, mgr.totalOrderCount());                   // only AAPL #1 left
    }
}
