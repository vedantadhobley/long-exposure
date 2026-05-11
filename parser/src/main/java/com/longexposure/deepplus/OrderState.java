package com.longexposure.deepplus;

/**
 * Per-order state tracked by an {@link OrderBook}. Constructed when an
 * {@link AddOrder} message arrives, mutated (via replacement, since records
 * are immutable) on each {@link OrderModify} / {@link OrderExecuted} that
 * references the same {@code orderId}, removed on {@link OrderDelete} or
 * when an {@link OrderExecuted} brings the size to zero.
 *
 * <p>Two timestamps are kept on every order:
 * <ul>
 *   <li>{@code addedNanos} — when the order was first added. Never changes;
 *       enables narratives about absolute order age.
 *   <li>{@code priorityNanos} — when the order's current time-priority on
 *       the book was established. Set to {@code addedNanos} on
 *       {@link AddOrder}, and updated to the {@link OrderModify}'s
 *       timestamp whenever a modify resets priority (Modify Flags bit 0
 *       = 0). A size-only modify that maintains priority leaves it alone.
 * </ul>
 *
 * <p>Scorers that look at queue-position behavior or time-at-current-state
 * (a key DEEP+ narrative input) read {@code priorityNanos}, not
 * {@code addedNanos}.
 *
 * <p>Package-private — order state is an internal implementation detail of
 * the book. External consumers see only the derived views
 * ({@code bestBidPriceRaw}, {@code bidLevels()}, etc.).
 */
record OrderState(
        AddOrder.Side side,
        long priceRaw,
        int size,
        long addedNanos,
        long priorityNanos) {

    OrderState withSize(final int newSize) {
        return new OrderState(side, priceRaw, newSize, addedNanos, priorityNanos);
    }

    OrderState withModification(final int newSize, final long newPriceRaw, final boolean priorityReset, final long modifyNanos) {
        long newPriority = priorityReset ? modifyNanos : priorityNanos;
        return new OrderState(side, newPriceRaw, newSize, addedNanos, newPriority);
    }
}
