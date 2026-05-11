package com.longexposure.admin;

/**
 * Short Sale Price Test Status Message — {@code P} (0x50), 19 bytes.
 *
 * <p>Reg SHO Rule 201 short-sale price-test restriction status, plus a
 * 1-byte Detail field giving the why-now (intraday drop / continued from
 * prior day / deactivated / etc).
 *
 * <pre>
 * Offset  Size  Field
 *     0     1   Message Type (0x50)
 *     1     1   Status (0 or 1)
 *     2     8   Timestamp
 *    10     8   Symbol
 *    18     1   Detail
 * </pre>
 */
public record ShortSalePriceTestStatus(Status status, long timestampNanos, String symbol, Detail detail)
        implements AdminMessage {

    public static final byte MESSAGE_TYPE = (byte) 0x50;
    public static final int BYTE_LENGTH = 19;

    @Override
    public byte messageType() {
        return MESSAGE_TYPE;
    }

    public static ShortSalePriceTestStatus decode(final byte[] buf, final int offset) {
        Bytes.requireLength(buf, offset, BYTE_LENGTH, "ShortSalePriceTestStatus");
        Status status = Status.fromByte(buf[offset + 1]);
        long ts = Bytes.readLongLE(buf, offset + 2);
        String symbol = Bytes.decodeSymbol(buf, offset + 10);
        Detail detail = Detail.fromByte(buf[offset + 18]);
        return new ShortSalePriceTestStatus(status, ts, symbol, detail);
    }

    public enum Status {
        NOT_IN_EFFECT((byte) 0x00),
        IN_EFFECT((byte) 0x01);

        public final byte value;

        Status(final byte value) {
            this.value = value;
        }

        public static Status fromByte(final byte b) {
            for (Status s : values()) {
                if (s.value == b) return s;
            }
            throw new IllegalArgumentException(
                    String.format("Unknown ShortSalePriceTestStatus: 0x%02x", b & 0xff));
        }
    }

    public enum Detail {
        NO_PRICE_TEST((byte) 0x20),     // space — no price test in place
        ACTIVATED((byte) 0x41),         // 'A' — intraday price drop
        CONTINUED((byte) 0x43),         // 'C' — continued from prior day
        DEACTIVATED((byte) 0x44),       // 'D'
        DETAIL_NOT_AVAILABLE((byte) 0x4e); // 'N' — non-IEX-listed securities

        public final byte value;

        Detail(final byte value) {
            this.value = value;
        }

        public static Detail fromByte(final byte b) {
            for (Detail d : values()) {
                if (d.value == b) return d;
            }
            throw new IllegalArgumentException(
                    String.format("Unknown ShortSalePriceTestStatus Detail: 0x%02x", b & 0xff));
        }
    }
}
