package com.longexposure.deepplus;

import com.longexposure.wire.Bytes;

/**
 * Order Modify Message — {@code M} (0x4D), 38 bytes.
 *
 * <p>A displayed order had its Price, Size, or Priority component changed.
 * The {@code orderId} references the original {@link AddOrder}'s ID.
 *
 * <p>{@link #maintainPriority()} reports whether the modification preserved
 * the order's time-priority on the book (bit 0 of Modify Flags = 1) or
 * reset it (bit 0 = 0). Price changes always reset priority; size-reductions
 * typically maintain it.
 *
 * <pre>
 * Offset  Size  Field
 *     0     1   Message Type (0x4D)
 *     1     1   Modify Flags (bit 0 = Maintain Priority; bits 1-7 reserved)
 *     2     8   Timestamp
 *    10     8   Symbol
 *    18     8   Order ID Reference
 *    26     4   Size (new total quoted size)
 *    30     8   Price (new booking price)
 * </pre>
 */
public record OrderModify(
        byte modifyFlags,
        long timestampNanos,
        String symbol,
        long orderId,
        int size,
        long priceRaw) implements DeepPlusMessage {

    public static final byte MESSAGE_TYPE = (byte) 0x4D;
    public static final int BYTE_LENGTH = 38;

    private static final int FLAG_MAINTAIN_PRIORITY = 0x01;

    @Override
    public byte messageType() {
        return MESSAGE_TYPE;
    }

    public static OrderModify decode(final byte[] buf, final int offset) {
        Bytes.requireLength(buf, offset, BYTE_LENGTH, "OrderModify");
        byte flags   = buf[offset + 1];
        long ts      = Bytes.readLongLE(buf, offset + 2);
        String sym   = Bytes.decodeSymbol(buf, offset + 10);
        long orderId = Bytes.readLongLE(buf, offset + 18);
        int size     = Bytes.readIntLE(buf, offset + 26);
        long price   = Bytes.readLongLE(buf, offset + 30);
        return new OrderModify(flags, ts, sym, orderId, size, price);
    }

    public boolean maintainPriority() {
        return (modifyFlags & FLAG_MAINTAIN_PRIORITY) != 0;
    }

    public double price() {
        return priceRaw / 10_000.0;
    }
}
