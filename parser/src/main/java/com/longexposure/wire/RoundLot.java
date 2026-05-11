package com.longexposure.wire;

/**
 * Reg NMS tiered round-lot determination.
 *
 * <p>Per the SEC Market Data Infrastructure Rule (NMS Plan amendments
 * effective May 2024), the round lot for U.S. equities is no longer
 * universally 100 shares — it varies by price band:
 *
 * <table>
 *   <caption>Tiered round lots</caption>
 *   <tr><th>Price (per share)</th><th>Round lot</th></tr>
 *   <tr><td>≤ $250.00</td><td>100</td></tr>
 *   <tr><td>$250.01 – $1,000.00</td><td>40</td></tr>
 *   <tr><td>$1,000.01 – $10,000.00</td><td>10</td></tr>
 *   <tr><td>&gt; $10,000.00</td><td>1</td></tr>
 * </table>
 *
 * <p>This determines which price levels qualify as the protected
 * best bid/offer (TOPS BBO semantics). A level must have aggregate
 * displayed quantity at or above its tier's round-lot threshold.
 *
 * <p>The tiered rule explains observed BBO behavior on the 2026-05-08
 * IEX HIST data — e.g. TOPS publishes an 80-share ask at $502.11 on
 * SOXX (tier 2, lot=40), an 80-share ask at $520.00 on CRWD (tier 2),
 * and a 125-share ask at $7,025.06 on GME's stub quote (tier 3, lot=10).
 * A flat 100-share threshold misclassifies all of these.
 *
 * <p>{@code SecurityDirectory} messages declare a {@code Round Lot Size}
 * field per IEX-listed symbol, but in HIST .pcap.gz files those messages
 * are sparse (typically only IEX test symbols ZIEXT/ZEXIT/ZXIET appear),
 * so we derive the round lot from the price band instead.
 */
public final class RoundLot {

    /** Prices are 8-byte integers with 4 implied decimals (so $250.00 = 250_0000). */
    private static final long P_250    =       250_0000L;
    private static final long P_1000   =     1_000_0000L;
    private static final long P_10000  =    10_000_0000L;

    private RoundLot() {}

    /**
     * Tiered round-lot threshold for a price level expressed in IEX's
     * 4-implied-decimal raw integer encoding.
     */
    public static long forPriceRaw(final long priceRaw) {
        if (priceRaw <= P_250)   return 100L;
        if (priceRaw <= P_1000)  return  40L;
        if (priceRaw <= P_10000) return  10L;
        return 1L;
    }
}
