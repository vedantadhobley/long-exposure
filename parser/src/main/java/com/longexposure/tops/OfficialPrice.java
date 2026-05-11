package com.longexposure.tops;

import com.longexposure.wire.Bytes;

/**
 * Official Price Message — {@code X} (0x58), 26 bytes.
 *
 * <p>IEX Official Opening Price (set by the Opening Auction) and IEX
 * Official Closing Price (set by the Closing Auction), per IEX-listed
 * security. Each new Official Price for a given Price Type supersedes
 * the prior one.
 *
 * <pre>
 * Offset  Size  Field
 *     0     1   Message Type (0x58)
 *     1     1   Price Type (Q = opening, M = closing)
 *     2     8   Timestamp
 *    10     8   Symbol
 *    18     8   Official Price
 * </pre>
 */
public record OfficialPrice(
        PriceType priceType,
        long timestampNanos,
        String symbol,
        long priceRaw) implements TopsMessage {

    public static final byte MESSAGE_TYPE = (byte) 0x58;
    public static final int BYTE_LENGTH = 26;

    @Override
    public byte messageType() {
        return MESSAGE_TYPE;
    }

    public static OfficialPrice decode(final byte[] buf, final int offset) {
        Bytes.requireLength(buf, offset, BYTE_LENGTH, "OfficialPrice");
        PriceType type = PriceType.fromByte(buf[offset + 1]);
        long ts        = Bytes.readLongLE(buf, offset + 2);
        String symbol  = Bytes.decodeSymbol(buf, offset + 10);
        long price     = Bytes.readLongLE(buf, offset + 18);
        return new OfficialPrice(type, ts, symbol, price);
    }

    public double price() {
        return priceRaw / 10_000.0;
    }

    public enum PriceType {
        OPENING((byte) 0x51),   // 'Q'
        CLOSING((byte) 0x4d);   // 'M'

        public final byte value;

        PriceType(final byte value) {
            this.value = value;
        }

        public static PriceType fromByte(final byte b) {
            for (PriceType p : values()) {
                if (p.value == b) return p;
            }
            throw new IllegalArgumentException(
                    String.format("Unknown OfficialPrice PriceType: 0x%02x", b & 0xff));
        }
    }
}
