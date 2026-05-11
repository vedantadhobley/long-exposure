package com.longexposure.dpls;

import com.longexposure.wire.ProtectedBbo;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Per-symbol order book reconstructed from a DPLS event stream.
 *
 * <p>Maintains two views simultaneously:
 * <ul>
 *   <li>{@code ordersById} — every live order keyed by IEX-assigned Order ID,
 *       for O(1) lookup on Modify / Delete / Execute messages.
 *   <li>{@code sizeAtBidPrice}, {@code sizeAtAskPrice} — aggregate shares at
 *       each price level on each side, kept incrementally so BBO and depth
 *       queries are O(log n).
 * </ul>
 *
 * <p>Strict-mode invariants (throw on violation; surface bugs early):
 * <ul>
 *   <li>{@link #apply(AddOrder)} with an Order ID already present
 *   <li>{@link #apply(OrderModify)} / {@link #apply(OrderDelete)} /
 *       {@link #apply(OrderExecuted)} for an Order ID not in the book
 *   <li>{@link #apply(OrderExecuted)} with executed size greater than the
 *       order's remaining size
 *   <li>Aggregate size at a price level going negative
 * </ul>
 *
 * <p>Not thread-safe. One book per symbol; multiple symbols are managed by
 * {@link OrderBookManager}.
 *
 * <p>Per the DPLS spec, Trade ({@code T}) and Trade Break ({@code B})
 * messages do not modify the displayed book — they pertain to
 * non-displayed-vs-non-displayed executions and to broken-trade
 * notifications respectively. {@link OrderBookManager} handles those at a
 * higher level by not dispatching them here.
 */
public final class OrderBook {

    private final String symbol;
    private final Map<Long, OrderState> ordersById = new HashMap<>();
    /** price → aggregate shares on the bid side; lastKey() = best bid. */
    private final TreeMap<Long, Long> sizeAtBidPrice = new TreeMap<>();
    /** price → aggregate shares on the ask side; firstKey() = best ask. */
    private final TreeMap<Long, Long> sizeAtAskPrice = new TreeMap<>();

    public OrderBook(final String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    // ─── apply (mutate state) ────────────────────────────────────────────────

    public void apply(final AddOrder m) {
        if (ordersById.containsKey(m.orderId())) {
            throw new IllegalStateException(symbol + ": AddOrder for orderId "
                    + m.orderId() + " but that ID is already on the book");
        }
        OrderState s = new OrderState(
                m.side(), m.priceRaw(), m.size(), m.timestampNanos(), m.timestampNanos());
        ordersById.put(m.orderId(), s);
        addToLevel(s.side(), s.priceRaw(), s.size());
    }

    public void apply(final OrderModify m) {
        OrderState existing = requireOrder(m.orderId(), "OrderModify");
        // Remove from the old level (could be a different price)
        removeFromLevel(existing.side(), existing.priceRaw(), existing.size());
        // Apply the modification; priority resets unless flag bit 0 is set
        boolean priorityReset = !m.maintainPriority();
        OrderState modified = existing.withModification(
                m.size(), m.priceRaw(), priorityReset, m.timestampNanos());
        ordersById.put(m.orderId(), modified);
        addToLevel(modified.side(), modified.priceRaw(), modified.size());
    }

    public void apply(final OrderDelete m) {
        OrderState existing = requireOrder(m.orderId(), "OrderDelete");
        ordersById.remove(m.orderId());
        removeFromLevel(existing.side(), existing.priceRaw(), existing.size());
    }

    public void apply(final OrderExecuted m) {
        OrderState existing = requireOrder(m.orderId(), "OrderExecuted");
        int executed = m.size();
        if (executed > existing.size()) {
            throw new IllegalStateException(symbol + ": OrderExecuted size "
                    + executed + " exceeds remaining size " + existing.size()
                    + " for orderId " + m.orderId());
        }
        int remaining = existing.size() - executed;
        removeFromLevel(existing.side(), existing.priceRaw(), executed);
        if (remaining == 0) {
            ordersById.remove(m.orderId());
        } else {
            ordersById.put(m.orderId(), existing.withSize(remaining));
        }
    }

    /** Drop every order for this symbol (response to {@code C} Clear Book). */
    public void clear() {
        ordersById.clear();
        sizeAtBidPrice.clear();
        sizeAtAskPrice.clear();
    }

    // ─── per-level housekeeping ──────────────────────────────────────────────

    private void addToLevel(final AddOrder.Side side, final long priceRaw, final long sharesAdded) {
        levelMap(side).merge(priceRaw, sharesAdded, Long::sum);
    }

    private void removeFromLevel(final AddOrder.Side side, final long priceRaw, final long sharesRemoved) {
        TreeMap<Long, Long> levels = levelMap(side);
        Long current = levels.get(priceRaw);
        if (current == null) {
            throw new IllegalStateException(symbol + ": expected level " + priceRaw
                    + " on " + side + " side but it was missing");
        }
        long updated = current - sharesRemoved;
        if (updated < 0) {
            throw new IllegalStateException(symbol + ": level " + priceRaw + " on " + side
                    + " side went negative (had " + current + ", removing " + sharesRemoved + ")");
        }
        if (updated == 0) {
            levels.remove(priceRaw);
        } else {
            levels.put(priceRaw, updated);
        }
    }

    private TreeMap<Long, Long> levelMap(final AddOrder.Side side) {
        return side == AddOrder.Side.BUY ? sizeAtBidPrice : sizeAtAskPrice;
    }

    private OrderState requireOrder(final long orderId, final String op) {
        OrderState s = ordersById.get(orderId);
        if (s == null) {
            throw new IllegalStateException(symbol + ": " + op + " for unknown orderId " + orderId);
        }
        return s;
    }

    // ─── derived views (read-only) ───────────────────────────────────────────

    public boolean isEmpty() {
        return ordersById.isEmpty();
    }

    public int orderCount() {
        return ordersById.size();
    }

    /** Live order state for {@code orderId}, or {@code null} if unknown. */
    public OrderState get(final long orderId) {
        return ordersById.get(orderId);
    }

    public boolean contains(final long orderId) {
        return ordersById.containsKey(orderId);
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
     * Round-lot-protected best bid using TOPS BBO semantics — Reg NMS
     * tiered round lot plus odd-lot-above aggregation. The returned
     * record's {@code priceRaw} is the highest qualifying bid level,
     * {@code size} is the sum at that price plus all shares at any
     * better odd-lot-only level above. See {@link ProtectedBbo} for the
     * full definition and the empirical fit against 2026-05-08 HIST.
     *
     * <p>Use this in any comparison against a TOPS {@code QuoteUpdate}.
     * Use {@link #bestBidPriceRaw()} / {@link #sizeAtBestBid()} for the
     * raw unprotected best (e.g. for internal analysis).
     */
    public ProtectedBbo bestBidProtected() {
        return ProtectedBbo.from(sizeAtBidPrice.descendingMap());
    }

    /** TOPS-semantics best ask. See {@link #bestBidProtected()}. */
    public ProtectedBbo bestAskProtected() {
        return ProtectedBbo.from(sizeAtAskPrice);
    }

    /**
     * Round-lot-protected BBO using an explicit per-symbol round lot
     * (e.g. derived from the symbol's prior-day close). Pass {@code 0}
     * to fall back to the per-level-price tier.
     */
    public ProtectedBbo bestBidProtected(final long fixedRoundLot) {
        return ProtectedBbo.from(sizeAtBidPrice.descendingMap(), fixedRoundLot);
    }

    public ProtectedBbo bestAskProtected(final long fixedRoundLot) {
        return ProtectedBbo.from(sizeAtAskPrice, fixedRoundLot);
    }

    /**
     * Fixed-threshold round-lot best bid: highest bid price whose own
     * aggregate is at least {@code minSize}. Simpler than
     * {@link #bestBidProtected()} (no aggregation, no tier table) —
     * kept for tests and ad-hoc analysis where a single threshold is
     * the right knob. Returned size is the level's own aggregate only.
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

    public long totalBidSize() {
        long sum = 0L;
        for (long s : sizeAtBidPrice.values()) sum += s;
        return sum;
    }

    public long totalAskSize() {
        long sum = 0L;
        for (long s : sizeAtAskPrice.values()) sum += s;
        return sum;
    }

    /** Bid levels, descending by price (highest first). Unmodifiable view. */
    public NavigableMap<Long, Long> bidLevels() {
        return Collections.unmodifiableNavigableMap(sizeAtBidPrice.descendingMap());
    }

    /** Ask levels, ascending by price (lowest first). Unmodifiable view. */
    public NavigableMap<Long, Long> askLevels() {
        return Collections.unmodifiableNavigableMap(sizeAtAskPrice);
    }

    public int bidLevelCount() {
        return sizeAtBidPrice.size();
    }

    public int askLevelCount() {
        return sizeAtAskPrice.size();
    }

    /**
     * Aggregate displayed size at one specific (side, price) — what DEEP's
     * {@link com.longexposure.deep.PriceLevelUpdate} reports for the same
     * level. Used by the DEEP-vs-DPLS price-level cross-validator. Returns
     * 0 if no order rests at that exact price on that side.
     */
    public long aggregateAt(final AddOrder.Side side, final long priceRaw) {
        Long v = levelMap(side).get(priceRaw);
        return v == null ? 0L : v;
    }

    /**
     * Aggregate size at the Nth-best bid level (1-indexed; level 1 = best bid).
     * Returns 0 if the book has fewer than N levels on this side.
     */
    public long sizeAtBidLevel(final int level) {
        return sizeAtLevel(sizeAtBidPrice.descendingMap(), level);
    }

    public long sizeAtAskLevel(final int level) {
        return sizeAtLevel(sizeAtAskPrice, level);
    }

    private static long sizeAtLevel(final Map<Long, Long> ordered, final int level) {
        if (level < 1) throw new IllegalArgumentException("level must be >= 1, got " + level);
        int i = 1;
        for (long sz : ordered.values()) {
            if (i == level) return sz;
            i++;
        }
        return 0L;
    }
}
