package com.longexposure.deep;

import com.longexposure.wire.IexMessage;

/**
 * Sealed marker for DEEP-feed-specific trading messages.
 *
 * <p>DEEP is the price-level view of the IEX displayed book: every change
 * in aggregate displayed size at any price level is published as a
 * {@link PriceLevelUpdate}. It sits between TOPS (best-bid-and-offer only)
 * and DPLS (order-by-order).
 *
 * <p>Five other trading messages — {@code T} TradeReport, {@code B} TradeBreak,
 * {@code X} OfficialPrice, and {@code A} AuctionInformation — are byte-identical
 * to the TOPS-feed versions and reused as-is from {@code com.longexposure.tops}
 * via {@link DeepMessageRouter}. Only the DEEP-unique price-level updates
 * appear under this sealed hierarchy.
 *
 * <p>Use {@link DeepMessageRouter#decode(byte, byte[], int)} to dispatch
 * any DEEP message (admin, DEEP-unique, or feed-shared) by 1-byte type
 * identifier.
 */
public sealed interface DeepMessage extends IexMessage permits PriceLevelUpdate {
}
