package com.longexposure.wire;

import java.util.Map;
import java.util.NavigableMap;

/**
 * Round-lot-protected best bid/offer computation, matching TOPS
 * {@code QuoteUpdate} semantics. Used to derive a comparable BBO from
 * either the DPLS order-level book or the DEEP price-level book.
 *
 * <p><b>The rule (two parts):</b>
 * <ol>
 *   <li>BBO price = the first price level (from best to worst) whose
 *       aggregate displayed size meets its tier's round-lot threshold
 *       (see {@link RoundLot}).
 *   <li>BBO size = sum of displayed shares at the BBO price <em>plus</em>
 *       all shares at any "better" price levels above it (which by
 *       definition are odd-lot-only levels that failed step 1).
 * </ol>
 *
 * <p>Empirical fit verified against 2026-05-08 IEX HIST data: the
 * combination of tiered threshold + odd-lot-above-aggregation reproduces
 * TOPS BBO sizes for every mismatch pattern surfaced by the validator:
 *
 * <pre>
 *   MASK   $2.89 ask: 100 round + 47 odd at $2.86 → TOPS reports 147 @ $2.89
 *   SMR   $12.44 bid: 100 round + 75 odd at $12.45 → TOPS reports 175 @ $12.44
 *   SOXX $502.11 ask:  80 (tier-2 round=40)        → TOPS reports  80 @ $502.11
 *   GME $7025.06 ask: 125 (tier-3 round=10)        → TOPS reports 125 @ $7025.06
 * </pre>
 *
 * <p>The "size includes better-priced odd lots" rule mirrors Reg NMS's
 * protected-quote concept: at the protected BBO price, you can pick up
 * not just the round-lot at that price but every better-priced odd lot
 * sitting above it.
 */
public record ProtectedBbo(long priceRaw, long size) {

    public static final ProtectedBbo EMPTY = new ProtectedBbo(0L, 0L);

    public boolean isPresent() { return size > 0L; }

    /**
     * Compute the protected BBO from a side's level map ordered best-first.
     * For bids pass {@code TreeMap.descendingMap()} (highest price first);
     * for asks pass the natural {@code TreeMap} order (lowest first).
     */
    public static ProtectedBbo from(final NavigableMap<Long, Long> bestFirstLevels) {
        long cumulative = 0L;
        for (Map.Entry<Long, Long> e : bestFirstLevels.entrySet()) {
            long levelPrice = e.getKey();
            long levelSize = e.getValue();
            cumulative += levelSize;
            if (levelSize >= RoundLot.forPriceRaw(levelPrice)) {
                return new ProtectedBbo(levelPrice, cumulative);
            }
        }
        return EMPTY;
    }
}
