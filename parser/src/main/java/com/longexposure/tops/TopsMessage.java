package com.longexposure.tops;

import com.longexposure.wire.IexMessage;

/**
 * Sealed marker for the 5 TOPS-feed-specific trading messages.
 * Distinct from the 7 byte-identical admin messages in
 * {@code com.longexposure.admin}.
 *
 * <p>Use {@link TopsMessageRouter#decode(byte, byte[], int)} to dispatch
 * any TOPS message — admin OR trading — by 1-byte type identifier.
 */
public sealed interface TopsMessage extends IexMessage permits
        QuoteUpdate,
        TradeReport,
        TradeBreak,
        OfficialPrice,
        AuctionInformation {
}
