package com.longexposure.dpls;

import com.longexposure.wire.Bytes;
import com.longexposure.wire.SaleConditionFlags;

/**
 * Trade Break Message — {@code B} (0x42), 38 bytes.
 *
 * <p>An execution on IEX was broken on the same trading day. The
 * {@code brokenTradeId} references the original
 * {@link OrderExecuted#tradeId()} or {@link Trade#tradeId()}.
 * Wire format is identical to {@link Trade}.
 *
 * <pre>
 * Offset  Size  Field
 *     0     1   Message Type (0x42)
 *     1     1   Sale Condition Flags
 *     2     8   Timestamp
 *    10     8   Symbol
 *    18     4   Size
 *    22     8   Price
 *    30     8   Trade ID (refers to broken trade)
 * </pre>
 */
public record TradeBreak(
        byte flags,
        long timestampNanos,
        String symbol,
        int size,
        long priceRaw,
        long brokenTradeId) implements DplsMessage {

    public static final byte MESSAGE_TYPE = (byte) 0x42;
    public static final int BYTE_LENGTH = 38;

    @Override
    public byte messageType() {
        return MESSAGE_TYPE;
    }

    public static TradeBreak decode(final byte[] buf, final int offset) {
        Bytes.requireLength(buf, offset, BYTE_LENGTH, "TradeBreak");
        byte flags   = buf[offset + 1];
        long ts      = Bytes.readLongLE(buf, offset + 2);
        String sym   = Bytes.decodeSymbol(buf, offset + 10);
        int size     = Bytes.readIntLE(buf, offset + 18);
        long price   = Bytes.readLongLE(buf, offset + 22);
        long tradeId = Bytes.readLongLE(buf, offset + 30);
        return new TradeBreak(flags, ts, sym, size, price, tradeId);
    }

    public SaleConditionFlags conditions() {
        return new SaleConditionFlags(flags);
    }

    public double price() {
        return priceRaw / 10_000.0;
    }
}
