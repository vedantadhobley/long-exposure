package com.longexposure.admin;

/**
 * Retail Liquidity Indicator Message — {@code I} (0x49), 18 bytes.
 *
 * <p>Indicates the current retail liquidity interest for a symbol. Prior
 * to trading IEX publishes a "no interest indicator" (0x20 / space) for
 * every symbol.
 *
 * <pre>
 * Offset  Size  Field
 *     0     1   Message Type (0x49)
 *     1     1   Indicator
 *     2     8   Timestamp
 *    10     8   Symbol
 * </pre>
 */
public record RetailLiquidityIndicator(Indicator indicator, long timestampNanos, String symbol)
        implements AdminMessage {

    public static final byte MESSAGE_TYPE = (byte) 0x49;
    public static final int BYTE_LENGTH = 18;

    @Override
    public byte messageType() {
        return MESSAGE_TYPE;
    }

    public static RetailLiquidityIndicator decode(final byte[] buf, final int offset) {
        Bytes.requireLength(buf, offset, BYTE_LENGTH, "RetailLiquidityIndicator");
        Indicator indicator = Indicator.fromByte(buf[offset + 1]);
        long ts = Bytes.readLongLE(buf, offset + 2);
        String symbol = Bytes.decodeSymbol(buf, offset + 10);
        return new RetailLiquidityIndicator(indicator, ts, symbol);
    }

    public enum Indicator {
        NOT_APPLICABLE((byte) 0x20),  // space
        BUY((byte) 0x41),             // 'A'
        SELL((byte) 0x42),            // 'B'
        BOTH((byte) 0x43);            // 'C' — buy and sell interest

        public final byte value;

        Indicator(final byte value) {
            this.value = value;
        }

        public static Indicator fromByte(final byte b) {
            for (Indicator i : values()) {
                if (i.value == b) return i;
            }
            throw new IllegalArgumentException(
                    String.format("Unknown RetailLiquidityIndicator: 0x%02x", b & 0xff));
        }
    }
}
