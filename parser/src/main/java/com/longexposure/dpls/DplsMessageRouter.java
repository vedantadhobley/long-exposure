package com.longexposure.dpls;

import com.longexposure.admin.AdminMessage;
import com.longexposure.admin.AdminMessages;
import com.longexposure.wire.IexMessage;

/**
 * Decode a DPLS message (admin or trading) by its 1-byte type identifier.
 *
 * <p>Admin types are tried first via {@link AdminMessages#decode}; if the
 * type byte isn't an admin message, the DPLS-specific decoders are tried.
 * Unknown types throw.
 *
 * <p>Mirrors the shape of {@code TopsMessageRouter} exactly — admin dispatch
 * is shared identical infrastructure across feeds.
 */
public final class DplsMessageRouter {

    private DplsMessageRouter() {}

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
            case AddOrder.MESSAGE_TYPE      -> AddOrder.decode(buf, offset);
            case OrderModify.MESSAGE_TYPE   -> OrderModify.decode(buf, offset);
            case OrderDelete.MESSAGE_TYPE   -> OrderDelete.decode(buf, offset);
            case OrderExecuted.MESSAGE_TYPE -> OrderExecuted.decode(buf, offset);
            case Trade.MESSAGE_TYPE         -> Trade.decode(buf, offset);
            case TradeBreak.MESSAGE_TYPE    -> TradeBreak.decode(buf, offset);
            case ClearBook.MESSAGE_TYPE     -> ClearBook.decode(buf, offset);
            default -> throw new IllegalArgumentException(
                    String.format("Unknown DPLS message type: 0x%02x", messageType & 0xff));
        };
    }

    /** True if {@code messageType} is one of the 7 DPLS-specific trading types. */
    public static boolean isDplsTradingType(final byte messageType) {
        return switch (messageType) {
            case AddOrder.MESSAGE_TYPE,
                 OrderModify.MESSAGE_TYPE,
                 OrderDelete.MESSAGE_TYPE,
                 OrderExecuted.MESSAGE_TYPE,
                 Trade.MESSAGE_TYPE,
                 TradeBreak.MESSAGE_TYPE,
                 ClearBook.MESSAGE_TYPE -> true;
            default -> false;
        };
    }
}
