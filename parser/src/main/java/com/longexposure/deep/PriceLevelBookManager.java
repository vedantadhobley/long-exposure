package com.longexposure.deep;

import com.longexposure.wire.IexMessage;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds per-symbol {@link PriceLevelBook}s and routes incoming DEEP
 * messages to the right one. Mirrors
 * {@link com.longexposure.dpls.OrderBookManager} but for the
 * price-level (not order-level) view.
 *
 * <p>Only {@link PriceLevelUpdate} touches the book; admin messages,
 * trades, trade breaks, official prices, and auction info are silently
 * ignored at this layer (they're either book-state-irrelevant or
 * lifecycle-only).
 */
public final class PriceLevelBookManager {

    private final Map<String, PriceLevelBook> bySymbol = new HashMap<>();

    /** Route any DEEP message; only {@link PriceLevelUpdate} affects book state. */
    public void apply(final IexMessage m) {
        if (m instanceof PriceLevelUpdate plu) {
            bookFor(plu.symbol()).apply(plu);
        }
    }

    public PriceLevelBook book(final String symbol) {
        return bySymbol.get(symbol);
    }

    private PriceLevelBook bookFor(final String symbol) {
        return bySymbol.computeIfAbsent(symbol, PriceLevelBook::new);
    }

    public int symbolCount() {
        return bySymbol.size();
    }
}
