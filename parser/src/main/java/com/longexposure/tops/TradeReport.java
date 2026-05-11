package com.longexposure.tops;

import com.longexposure.wire.Bytes;
import com.longexposure.wire.SaleConditionFlags;

/**
 * Trade Report Message — {@code T} (0x54), 38 bytes.
 *
 * <p>Emitted for every individual fill on the IEX Order Book (displayed
 * or non-displayed). Routed executions are not reported.
 *
 * <pre>
 * Offset  Size  Field
 *     0     1   Message Type (0x54)
 *     1     1   Sale Condition Flags
 *     2     8   Timestamp
 *    10     8   Symbol
 *    18     4   Size (shares)
 *    22     8   Price
 *    30     8   Trade ID (joins to Trade Break)
 * </pre>
 */
public record TradeReport(
        byte flags,
        long timestampNanos,
        String symbol,
        int size,
        long priceRaw,
        long tradeId) implements TopsMessage {

    public static final byte MESSAGE_TYPE = (byte) 0x54;
    public static final int BYTE_LENGTH = 38;

    @Override
    public byte messageType() {
        return MESSAGE_TYPE;
    }

    public static TradeReport decode(final byte[] buf, final int offset) {
        Bytes.requireLength(buf, offset, BYTE_LENGTH, "TradeReport");
        byte flags    = buf[offset + 1];
        long ts       = Bytes.readLongLE(buf, offset + 2);
        String symbol = Bytes.decodeSymbol(buf, offset + 10);
        int size      = Bytes.readIntLE(buf, offset + 18);
        long price    = Bytes.readLongLE(buf, offset + 22);
        long tradeId  = Bytes.readLongLE(buf, offset + 30);
        return new TradeReport(flags, ts, symbol, size, price, tradeId);
    }

    public SaleConditionFlags conditions() {
        return new SaleConditionFlags(flags);
    }

    public double price() {
        return priceRaw / 10_000.0;
    }
}
