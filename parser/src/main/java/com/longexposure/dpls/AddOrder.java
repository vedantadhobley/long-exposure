package com.longexposure.dpls;

import com.longexposure.wire.Bytes;

/**
 * Add Order Message — {@code a} (0x61), 38 bytes.
 *
 * <p>A displayed order has been added to the IEX Book. The {@code orderId}
 * uniquely identifies this order for the remainder of the session;
 * subsequent {@link OrderModify}, {@link OrderDelete}, and
 * {@link OrderExecuted} messages reference it.
 *
 * <pre>
 * Offset  Size  Field
 *     0     1   Message Type (0x61)
 *     1     1   Side ('8' Buy / '5' Sell)
 *     2     8   Timestamp
 *    10     8   Symbol
 *    18     8   Order ID
 *    26     4   Size
 *    30     8   Price
 * </pre>
 */
public record AddOrder(
        Side side,
        long timestampNanos,
        String symbol,
        long orderId,
        int size,
        long priceRaw) implements DplsMessage {

    public static final byte MESSAGE_TYPE = (byte) 0x61;
    public static final int BYTE_LENGTH = 38;

    @Override
    public byte messageType() {
        return MESSAGE_TYPE;
    }

    public static AddOrder decode(final byte[] buf, final int offset) {
        Bytes.requireLength(buf, offset, BYTE_LENGTH, "AddOrder");
        Side side    = Side.fromByte(buf[offset + 1]);
        long ts      = Bytes.readLongLE(buf, offset + 2);
        String sym   = Bytes.decodeSymbol(buf, offset + 10);
        long orderId = Bytes.readLongLE(buf, offset + 18);
        int size     = Bytes.readIntLE(buf, offset + 26);
        long price   = Bytes.readLongLE(buf, offset + 30);
        return new AddOrder(side, ts, sym, orderId, size, price);
    }

    public double price() {
        return priceRaw / 10_000.0;
    }

    public enum Side {
        BUY((byte) 0x38),   // '8'
        SELL((byte) 0x35);  // '5'

        public final byte value;

        Side(final byte value) {
            this.value = value;
        }

        public static Side fromByte(final byte b) {
            for (Side s : values()) {
                if (s.value == b) return s;
            }
            throw new IllegalArgumentException(
                    String.format("Unknown AddOrder Side: 0x%02x", b & 0xff));
        }
    }
}
