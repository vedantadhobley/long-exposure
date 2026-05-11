package com.longexposure.deepplus;

import com.longexposure.wire.IexMessage;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Owns one {@link OrderBook} per symbol seen so far. Applies incoming
 * DEEP+ messages to the appropriate book based on the message's symbol.
 *
 * <p>Per the DEEP+ spec, only these message types affect the displayed
 * order book:
 * <ul>
 *   <li>{@link AddOrder} — insert new order
 *   <li>{@link OrderModify} — mutate existing order
 *   <li>{@link OrderDelete} — remove order
 *   <li>{@link OrderExecuted} — decrement order, remove if zero
 *   <li>{@link ClearBook} — drop all orders for the symbol
 * </ul>
 *
 * <p>{@code Trade} (T) and {@code TradeBreak} (B) reach the matching engine
 * but pertain to non-displayed liquidity, so {@link #apply(IexMessage)}
 * silently ignores them. The cumulative trade volume those carry is
 * tracked separately in {@code Main.java}'s aggregation map.
 *
 * <p>Admin messages (S/D/H/I/O/P/E) are also ignored — they don't touch
 * book state.
 *
 * <p>Not thread-safe.
 */
public final class OrderBookManager {

    private final Map<String, OrderBook> booksBySymbol = new HashMap<>();

    /**
     * Route a decoded DEEP+ message to the right book. Messages that don't
     * affect book state are silently ignored.
     */
    public void apply(final IexMessage m) {
        switch (m) {
            case AddOrder ao        -> bookFor(ao.symbol()).apply(ao);
            case OrderModify om     -> bookFor(om.symbol()).apply(om);
            case OrderDelete od     -> bookFor(od.symbol()).apply(od);
            case OrderExecuted oe   -> bookFor(oe.symbol()).apply(oe);
            case ClearBook cb       -> {
                OrderBook book = booksBySymbol.get(cb.symbol());
                if (book != null) book.clear();
            }
            default -> { /* Trade, TradeBreak, all admin types — book-state-irrelevant */ }
        }
    }

    /** Get-or-create the book for {@code symbol}. */
    private OrderBook bookFor(final String symbol) {
        return booksBySymbol.computeIfAbsent(symbol, OrderBook::new);
    }

    /** Existing book for {@code symbol}, or {@code null} if no events seen yet. */
    public OrderBook book(final String symbol) {
        return booksBySymbol.get(symbol);
    }

    public int symbolCount() {
        return booksBySymbol.size();
    }

    /** Total live orders across every symbol. Linear scan; for diagnostics. */
    public int totalOrderCount() {
        int sum = 0;
        for (OrderBook b : booksBySymbol.values()) sum += b.orderCount();
        return sum;
    }

    /** Read-only view of every active book. */
    public Map<String, OrderBook> books() {
        return Collections.unmodifiableMap(booksBySymbol);
    }
}
