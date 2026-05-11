package com.longexposure.admin;

/**
 * Dispatcher for the 7 byte-identical IEX admin messages. Per-feed routers
 * (TopsMessageRouter, DeepPlusMessageRouter, ...) call into this before
 * trying their feed-specific trading-message decoders.
 *
 * <pre>{@code
 * byte type = buf[offset];
 * AdminMessage m = AdminMessages.decode(type, buf, offset);
 * if (m != null) {
 *     handleAdmin(m);
 * } else {
 *     // hand off to the feed-specific decoder
 * }
 * }</pre>
 */
public final class AdminMessages {

    private AdminMessages() {}

    /** True if the given 1-byte message type identifies an admin message. */
    public static boolean isAdminType(final byte messageType) {
        return switch (messageType) {
            case SystemEvent.MESSAGE_TYPE,
                 SecurityDirectory.MESSAGE_TYPE,
                 TradingStatus.MESSAGE_TYPE,
                 RetailLiquidityIndicator.MESSAGE_TYPE,
                 OperationalHaltStatus.MESSAGE_TYPE,
                 ShortSalePriceTestStatus.MESSAGE_TYPE,
                 SecurityEvent.MESSAGE_TYPE -> true;
            default -> false;
        };
    }

    /**
     * Decode an admin message at {@code offset} in {@code buf}, dispatched
     * by the type byte. Returns {@code null} if {@code messageType} isn't
     * an admin type (caller should try feed-specific decoders).
     *
     * @throws IllegalArgumentException if the buffer is too short for the
     *         dispatched message type, or if a categorical field has an
     *         unrecognized value.
     */
    public static AdminMessage decode(final byte messageType, final byte[] buf, final int offset) {
        return switch (messageType) {
            case SystemEvent.MESSAGE_TYPE              -> SystemEvent.decode(buf, offset);
            case SecurityDirectory.MESSAGE_TYPE        -> SecurityDirectory.decode(buf, offset);
            case TradingStatus.MESSAGE_TYPE            -> TradingStatus.decode(buf, offset);
            case RetailLiquidityIndicator.MESSAGE_TYPE -> RetailLiquidityIndicator.decode(buf, offset);
            case OperationalHaltStatus.MESSAGE_TYPE    -> OperationalHaltStatus.decode(buf, offset);
            case ShortSalePriceTestStatus.MESSAGE_TYPE -> ShortSalePriceTestStatus.decode(buf, offset);
            case SecurityEvent.MESSAGE_TYPE            -> SecurityEvent.decode(buf, offset);
            default -> null;
        };
    }
}
