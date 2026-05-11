package com.longexposure.tops;

import com.longexposure.admin.AdminMessage;
import com.longexposure.admin.AdminMessages;
import com.longexposure.wire.IexMessage;

/**
 * Decode a TOPS message (admin or trading) by its 1-byte type identifier.
 *
 * <p>Admin types are tried first via {@link AdminMessages#decode}; if the
 * type byte isn't an admin message, the TOPS-specific decoders are tried.
 * Unknown types throw — TOPS 1.66 reserves room to grow but every type we
 * see in the 2026-era HIST data should be one of these 12.
 *
 * <p>Phase 2 will add a parallel {@code DeepPlusMessageRouter} sharing the
 * same admin dispatch.
 */
public final class TopsMessageRouter {

    private TopsMessageRouter() {}

    /**
     * Decode the message at {@code offset} in {@code buf} given its
     * 1-byte type. The type byte itself is at {@code buf[offset]}.
     *
     * @throws IllegalArgumentException for unknown type bytes or
     *         malformed message bodies
     */
    public static IexMessage decode(final byte messageType, final byte[] buf, final int offset) {
        AdminMessage admin = AdminMessages.decode(messageType, buf, offset);
        if (admin != null) {
            return admin;
        }
        return switch (messageType) {
            case QuoteUpdate.MESSAGE_TYPE         -> QuoteUpdate.decode(buf, offset);
            case TradeReport.MESSAGE_TYPE         -> TradeReport.decode(buf, offset);
            case TradeBreak.MESSAGE_TYPE          -> TradeBreak.decode(buf, offset);
            case OfficialPrice.MESSAGE_TYPE       -> OfficialPrice.decode(buf, offset);
            case AuctionInformation.MESSAGE_TYPE  -> AuctionInformation.decode(buf, offset);
            default -> throw new IllegalArgumentException(
                    String.format("Unknown TOPS message type: 0x%02x", messageType & 0xff));
        };
    }

    /** True if {@code messageType} is one of the 5 TOPS-specific trading types. */
    public static boolean isTopsTradingType(final byte messageType) {
        return switch (messageType) {
            case QuoteUpdate.MESSAGE_TYPE,
                 TradeReport.MESSAGE_TYPE,
                 TradeBreak.MESSAGE_TYPE,
                 OfficialPrice.MESSAGE_TYPE,
                 AuctionInformation.MESSAGE_TYPE -> true;
            default -> false;
        };
    }
}
