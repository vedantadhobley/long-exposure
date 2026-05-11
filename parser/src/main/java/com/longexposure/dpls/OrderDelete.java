package com.longexposure.dpls;

import com.longexposure.wire.Bytes;

/**
 * Order Delete Message — {@code R} (0x52), 26 bytes.
 *
 * <p>A displayed order was removed from the IEX Book (member-initiated
 * cancel or system action). The {@code orderId} is freed; if a subsequent
 * {@link AddOrder} re-uses the same ID, it begins a fresh tracked order.
 *
 * <pre>
 * Offset  Size  Field
 *     0     1   Message Type (0x52)
 *     1     1   Reserved
 *     2     8   Timestamp
 *    10     8   Symbol
 *    18     8   Order ID Reference
 * </pre>
 */
public record OrderDelete(
        long timestampNanos,
        String symbol,
        long orderId) implements DplsMessage {

    public static final byte MESSAGE_TYPE = (byte) 0x52;
    public static final int BYTE_LENGTH = 26;

    @Override
    public byte messageType() {
        return MESSAGE_TYPE;
    }

    public static OrderDelete decode(final byte[] buf, final int offset) {
        Bytes.requireLength(buf, offset, BYTE_LENGTH, "OrderDelete");
        long ts      = Bytes.readLongLE(buf, offset + 2);
        String sym   = Bytes.decodeSymbol(buf, offset + 10);
        long orderId = Bytes.readLongLE(buf, offset + 18);
        return new OrderDelete(ts, sym, orderId);
    }
}
