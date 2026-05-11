package com.longexposure.deep;

import com.longexposure.wire.ProtectedBbo;

import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Per-symbol price-level order book reconstructed from a DEEP event
 * stream. The DEEP analogue of {@link com.longexposure.dpls.OrderBook}.
 *
 * <p>DEEP publishes <em>aggregate</em> displayed size at each price level:
 * a single {@link PriceLevelUpdate} replaces the previous size at that
 * (symbol, side, price). There are no orderIds to track and no
 * Add/Modify/Execute lifecycle — every level change is a direct write.
 *
 * <p>Two views maintained:
 * <ul>
 *   <li>{@code sizeAtBidPrice} — price → aggregate size, descending = best bid first
 *   <li>{@code sizeAtAskPrice} — price → aggregate size, ascending  = best ask first
 * </ul>
 *
 * <p>{@link #applyClear()} drops both sides — DEEP doesn't have a
 * dedicated ClearBook message (unlike DPLS) but session boundaries and
 * cross-feed semantics may want it.
 *
 * <p>Not thread-safe.
 */
public final class PriceLevelBook {

    private final String symbol;
    private final TreeMap<Long, Long> sizeAtBidPrice = new TreeMap<>();
    private final TreeMap<Long, Long> sizeAtAskPrice = new TreeMap<>();

    public PriceLevelBook(final String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    /**
     * Apply a single PriceLevelUpdate. {@code size == 0} removes the level
     * entirely; any positive size replaces whatever was previously there
     * at that (side, price).
     *
     * <p>Note: this is direct-write semantics. The atomic-transaction rule
     * (mid-transaction PLUs with flag {@code 0x00} → final {@code 0x01})
     * is enforced by <em>callers</em> who should only query BBO after a
     * transaction-complete event. The book itself doesn't gate writes.
     */
    public void apply(final PriceLevelUpdate plu) {
        TreeMap<Long, Long> levels = sideMap(plu.side());
        if (plu.size() == 0) {
            levels.remove(plu.priceRaw());
        } else {
            levels.put(plu.priceRaw(), (long) plu.size());
        }
    }

    /** Drop every level on both sides. */
    public void applyClear() {
        sizeAtBidPrice.clear();
        sizeAtAskPrice.clear();
    }

    private TreeMap<Long, Long> sideMap(final PriceLevelUpdate.Side side) {
        return side == PriceLevelUpdate.Side.BUY ? sizeAtBidPrice : sizeAtAskPrice;
    }

    // ─── derived views ───────────────────────────────────────────────────────

    public boolean isEmpty() {
        return sizeAtBidPrice.isEmpty() && sizeAtAskPrice.isEmpty();
    }

    public int bidLevelCount() {
        return sizeAtBidPrice.size();
    }

    public int askLevelCount() {
        return sizeAtAskPrice.size();
    }

    public OptionalLong bestBidPriceRaw() {
        return sizeAtBidPrice.isEmpty() ? OptionalLong.empty() : OptionalLong.of(sizeAtBidPrice.lastKey());
    }

    public OptionalLong bestAskPriceRaw() {
        return sizeAtAskPrice.isEmpty() ? OptionalLong.empty() : OptionalLong.of(sizeAtAskPrice.firstKey());
    }

    public long sizeAtBestBid() {
        return sizeAtBidPrice.isEmpty() ? 0L : sizeAtBidPrice.lastEntry().getValue();
    }

    public long sizeAtBestAsk() {
        return sizeAtAskPrice.isEmpty() ? 0L : sizeAtAskPrice.firstEntry().getValue();
    }

    /**
     * Round-lot-protected best bid using TOPS BBO semantics. See
     * {@link ProtectedBbo} for the full definition.
     */
    public ProtectedBbo bestBidProtected() {
        return ProtectedBbo.from(sizeAtBidPrice.descendingMap());
    }

    public ProtectedBbo bestAskProtected() {
        return ProtectedBbo.from(sizeAtAskPrice);
    }

    /**
     * Fixed-threshold best bid (no tier table, no aggregation). Kept for
     * tests where a single threshold is the right knob.
     */
    public OptionalLong bestBidPriceRaw(final long minSize) {
        for (Map.Entry<Long, Long> e : sizeAtBidPrice.descendingMap().entrySet()) {
            if (e.getValue() >= minSize) return OptionalLong.of(e.getKey());
        }
        return OptionalLong.empty();
    }

    public OptionalLong bestAskPriceRaw(final long minSize) {
        for (Map.Entry<Long, Long> e : sizeAtAskPrice.entrySet()) {
            if (e.getValue() >= minSize) return OptionalLong.of(e.getKey());
        }
        return OptionalLong.empty();
    }

    public long sizeAtBestBid(final long minSize) {
        for (Map.Entry<Long, Long> e : sizeAtBidPrice.descendingMap().entrySet()) {
            if (e.getValue() >= minSize) return e.getValue();
        }
        return 0L;
    }

    public long sizeAtBestAsk(final long minSize) {
        for (Map.Entry<Long, Long> e : sizeAtAskPrice.entrySet()) {
            if (e.getValue() >= minSize) return e.getValue();
        }
        return 0L;
    }

    public NavigableMap<Long, Long> bidLevels() {
        return Collections.unmodifiableNavigableMap(sizeAtBidPrice.descendingMap());
    }

    public NavigableMap<Long, Long> askLevels() {
        return Collections.unmodifiableNavigableMap(sizeAtAskPrice);
    }
}
