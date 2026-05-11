package com.longexposure.dpls;

import com.longexposure.wire.Bytes;
import com.longexposure.wire.SaleConditionFlags;

/**
 * Order Executed Message — {@code L} (0x4C), 46 bytes.
 *
 * <p>A displayed order on the IEX Book was executed against. If the
 * remaining quantity reaches zero the order is removed from the book;
 * otherwise it stays with the same time priority. The executed price
 * may differ from the order's posted price due to IEX price improvement
 * or price-slide logic.
 *
 * <p>The {@code tradeId} is the canonical trade identifier — joins to
 * {@link TradeBreak} messages and matches the TOPS Trade Report
 * {@code tradeId} for cross-feed validation.
 *
 * <pre>
 * Offset  Size  Field
 *     0     1   Message Type (0x4C)
 *     1     1   Sale Condition Flags
 *     2     8   Timestamp
 *    10     8   Symbol
 *    18     8   Order ID Reference
 *    26     4   Size (executed quantity)
 *    30     8   Price (execution price)
 *    38     8   Trade ID
 * </pre>
 */
public record OrderExecuted(
        byte flags,
        long timestampNanos,
        String symbol,
        long orderId,
        int size,
        long priceRaw,
        long tradeId) implements DplsMessage {

    public static final byte MESSAGE_TYPE = (byte) 0x4C;
    public static final int BYTE_LENGTH = 46;

    @Override
    public byte messageType() {
        return MESSAGE_TYPE;
    }

    public static OrderExecuted decode(final byte[] buf, final int offset) {
        Bytes.requireLength(buf, offset, BYTE_LENGTH, "OrderExecuted");
        byte flags   = buf[offset + 1];
        long ts      = Bytes.readLongLE(buf, offset + 2);
        String sym   = Bytes.decodeSymbol(buf, offset + 10);
        long orderId = Bytes.readLongLE(buf, offset + 18);
        int size     = Bytes.readIntLE(buf, offset + 26);
        long price   = Bytes.readLongLE(buf, offset + 30);
        long tradeId = Bytes.readLongLE(buf, offset + 38);
        return new OrderExecuted(flags, ts, sym, orderId, size, price, tradeId);
    }

    public SaleConditionFlags conditions() {
        return new SaleConditionFlags(flags);
    }

    public double price() {
        return priceRaw / 10_000.0;
    }
}
