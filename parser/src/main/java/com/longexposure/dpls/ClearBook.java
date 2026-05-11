package com.longexposure.dpls;

import com.longexposure.wire.Bytes;

/**
 * Clear Book Message — {@code C} (0x43), 18 bytes.
 *
 * <p>The IEX Book for a symbol has been cleared of all orders. The
 * order-book state machine drops every {@code orderId} for the symbol
 * on receipt; subsequent {@link AddOrder} messages re-populate it.
 *
 * <pre>
 * Offset  Size  Field
 *     0     1   Message Type (0x43)
 *     1     1   Reserved
 *     2     8   Timestamp
 *    10     8   Symbol
 * </pre>
 */
public record ClearBook(
        long timestampNanos,
        String symbol) implements DplsMessage {

    public static final byte MESSAGE_TYPE = (byte) 0x43;
    public static final int BYTE_LENGTH = 18;

    @Override
    public byte messageType() {
        return MESSAGE_TYPE;
    }

    public static ClearBook decode(final byte[] buf, final int offset) {
        Bytes.requireLength(buf, offset, BYTE_LENGTH, "ClearBook");
        long ts    = Bytes.readLongLE(buf, offset + 2);
        String sym = Bytes.decodeSymbol(buf, offset + 10);
        return new ClearBook(ts, sym);
    }
}
