package com.longexposure.wire;

import java.util.Map;
import java.util.NavigableMap;

/**
 * Round-lot-protected best bid/offer computation, matching TOPS
 * {@code QuoteUpdate} semantics. Used to derive a comparable BBO from
 * either the DPLS order-level book or the DEEP price-level book.
 *
 * <p><b>The rule:</b> walk levels from best to worst, accumulating size.
 * The BBO is the first level at which the <em>running cumulative</em>
 * meets that level's round-lot threshold (see {@link RoundLot}). Size
 * reported is that cumulative total.
 *
 * <p><b>Why cumulative, not individual-per-level.</b> AIIO on 2026-05-08
 * had ask levels $1.13×18, $12.50×1, $67.45×90 — no single level reached
 * the 100-share tier-1 threshold, yet TOPS still reported BBO ask =
 * $67.45 × 109 (= 18 + 1 + 90). The cumulative rule captures this: by
 * $67.45 the running total crosses 100 and that level qualifies as
 * protected. Empirically refits every mismatch pattern surfaced by the
 * validator:
 *
 * <pre>
 *   MASK   $2.89 ask: 47 @ $2.86 + 100 @ $2.89  → cum=147 ≥ 100 → BBO=$2.89  ×147
 *   SMR   $12.44 bid: 75 @ $12.45 + 100 @ $12.44 → cum=175 ≥ 100 → BBO=$12.44 ×175
 *   SOXX $502.11 ask: 80 (tier-2 lot=40)         → cum=80  ≥  40 → BBO=$502.11 ×80
 *   AIIO  $67.45 ask: 18 + 1 + 90                → cum=109 ≥ 100 → BBO=$67.45  ×109
 * </pre>
 *
 * <p>This mirrors Reg NMS's "protected quote" concept: at the protected
 * BBO price, you can pick up the full cumulative quantity at the BBO
 * price and any better-priced odd lots above.
 *
 * <p>One known edge case still unhandled: tier-crossing stocks like GME
 * where the symbol's prior closing price determines the round-lot tier
 * (not the level price). In those cases this implementation under-uses
 * the threshold near the BBO and produces a different price than TOPS.
 * Fixing this requires per-symbol round-lot data, which isn't available
 * in HIST .pcap.gz outside the sparse SecurityDirectory messages.
 */
public record ProtectedBbo(long priceRaw, long size) {

    public static final ProtectedBbo EMPTY = new ProtectedBbo(0L, 0L);

    public boolean isPresent() { return size > 0L; }

    /**
     * Compute the protected BBO from a side's level map ordered best-first.
     * For bids pass {@code TreeMap.descendingMap()} (highest price first);
     * for asks pass the natural {@code TreeMap} order (lowest first).
     *
     * <p>This overload uses the level's own price to determine the
     * round-lot tier (best when prior-close data isn't available).
     */
    public static ProtectedBbo from(final NavigableMap<Long, Long> bestFirstLevels) {
        return from(bestFirstLevels, 0L);
    }

    /**
     * Same, but with an explicit per-symbol round-lot (e.g. derived
     * from the symbol's prior-day closing price). Pass {@code 0} to
     * fall back to per-level-price tier (equivalent to the no-arg
     * overload).
     *
     * <p>This is the closer-to-spec form — TOPS fixes the round-lot
     * for the trading day from yesterday's close, then holds it across
     * all intraday price moves. Tier-crossing stocks (LNG, GME, ASR,
     * BIO, etc.) only match TOPS BBO with the symbol-fixed round-lot.
     */
    public static ProtectedBbo from(final NavigableMap<Long, Long> bestFirstLevels,
                                    final long fixedRoundLot) {
        long cumulative = 0L;
        for (Map.Entry<Long, Long> e : bestFirstLevels.entrySet()) {
            long levelPrice = e.getKey();
            long levelSize = e.getValue();
            cumulative += levelSize;
            long roundLot = (fixedRoundLot > 0) ? fixedRoundLot : RoundLot.forPriceRaw(levelPrice);
            if (cumulative >= roundLot) {
                return new ProtectedBbo(levelPrice, cumulative);
            }
        }
        return EMPTY;
    }
}
