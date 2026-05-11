package com.longexposure.tops;

import com.longexposure.wire.Bytes;

/**
 * Quote Update Message — {@code Q} (0x51), 42 bytes.
 *
 * <p>Best bid/ask aggregate. Prior to trading IEX publishes a "zero quote"
 * (all four price/size fields = 0) for every symbol. Two flag bits in the
 * Flags byte expose symbol availability and market session.
 *
 * <pre>
 * Offset  Size  Field
 *     0     1   Message Type (0x51)
 *     1     1   Flags (bit 7 = symbol availability, bit 6 = market session)
 *     2     8   Timestamp
 *    10     8   Symbol
 *    18     4   Bid Size
 *    22     8   Bid Price
 *    30     8   Ask Price
 *    38     4   Ask Size
 * </pre>
 */
public record QuoteUpdate(
        byte flags,
        long timestampNanos,
        String symbol,
        int bidSize,
        long bidPriceRaw,
        long askPriceRaw,
        int askSize) implements TopsMessage {

    public static final byte MESSAGE_TYPE = (byte) 0x51;
    public static final int BYTE_LENGTH = 42;

    // Flag bits per TOPS 1.66 Appendix A:
    private static final int FLAG_SYMBOL_HALTED  = 0x80; // 1 = halted / paused / unavailable
    private static final int FLAG_OFF_HOURS      = 0x40; // 1 = pre/post-market session

    @Override
    public byte messageType() {
        return MESSAGE_TYPE;
    }

    public static QuoteUpdate decode(final byte[] buf, final int offset) {
        Bytes.requireLength(buf, offset, BYTE_LENGTH, "QuoteUpdate");
        byte flags    = buf[offset + 1];
        long ts       = Bytes.readLongLE(buf, offset + 2);
        String symbol = Bytes.decodeSymbol(buf, offset + 10);
        int bidSize   = Bytes.readIntLE(buf, offset + 18);
        long bid      = Bytes.readLongLE(buf, offset + 22);
        long ask      = Bytes.readLongLE(buf, offset + 30);
        int askSize   = Bytes.readIntLE(buf, offset + 38);
        return new QuoteUpdate(flags, ts, symbol, bidSize, bid, ask, askSize);
    }

    public boolean isSymbolHalted() {
        return (flags & FLAG_SYMBOL_HALTED) != 0;
    }

    public boolean isOffHoursSession() {
        return (flags & FLAG_OFF_HOURS) != 0;
    }

    public double bidPrice() { return bidPriceRaw / 10_000.0; }
    public double askPrice() { return askPriceRaw / 10_000.0; }
}
