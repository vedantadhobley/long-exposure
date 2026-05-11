package com.longexposure.dpls;

import com.longexposure.wire.Bytes;
import com.longexposure.wire.SaleConditionFlags;

/**
 * Trade Message — {@code T} (0x54), 38 bytes.
 *
 * <p>A non-displayed order on the book executed against another
 * non-displayed order on the book. <strong>Does not modify any displayed
 * order's quantity</strong> — book-state consumers can ignore this message.
 * It does contribute to cumulative executed volume on IEX, which is why
 * we still ingest it (for volume aggregation + cross-validation against
 * TOPS Trade Report totals).
 *
 * <p>For a TOPS-style total-volume check:
 * {@code SUM(OrderExecuted.size) + SUM(Trade.size)} per symbol per day
 * matches the {@code SUM(TOPS Trade Report.size)} on the same date.
 *
 * <pre>
 * Offset  Size  Field
 *     0     1   Message Type (0x54)
 *     1     1   Sale Condition Flags
 *     2     8   Timestamp
 *    10     8   Symbol
 *    18     4   Size
 *    22     8   Price
 *    30     8   Trade ID
 * </pre>
 */
public record Trade(
        byte flags,
        long timestampNanos,
        String symbol,
        int size,
        long priceRaw,
        long tradeId) implements DplsMessage {

    public static final byte MESSAGE_TYPE = (byte) 0x54;
    public static final int BYTE_LENGTH = 38;

    @Override
    public byte messageType() {
        return MESSAGE_TYPE;
    }

    public static Trade decode(final byte[] buf, final int offset) {
        Bytes.requireLength(buf, offset, BYTE_LENGTH, "Trade");
        byte flags   = buf[offset + 1];
        long ts      = Bytes.readLongLE(buf, offset + 2);
        String sym   = Bytes.decodeSymbol(buf, offset + 10);
        int size     = Bytes.readIntLE(buf, offset + 18);
        long price   = Bytes.readLongLE(buf, offset + 22);
        long tradeId = Bytes.readLongLE(buf, offset + 30);
        return new Trade(flags, ts, sym, size, price, tradeId);
    }

    public SaleConditionFlags conditions() {
        return new SaleConditionFlags(flags);
    }

    public double price() {
        return priceRaw / 10_000.0;
    }
}
