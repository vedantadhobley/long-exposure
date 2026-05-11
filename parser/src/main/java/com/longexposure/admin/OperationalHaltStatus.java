package com.longexposure.admin;

import com.longexposure.wire.Bytes;

/**
 * Operational Halt Status Message — {@code O} (0x4f), 18 bytes.
 *
 * <p>Indicates an IEX-specific (non-regulatory) operational halt for a
 * symbol. Distinct from a regulatory halt, which comes via
 * {@link TradingStatus}.
 *
 * <pre>
 * Offset  Size  Field
 *     0     1   Message Type (0x4f)
 *     1     1   Operational Halt Status (O or N)
 *     2     8   Timestamp
 *    10     8   Symbol
 * </pre>
 */
public record OperationalHaltStatus(Status status, long timestampNanos, String symbol)
        implements AdminMessage {

    public static final byte MESSAGE_TYPE = (byte) 0x4f;
    public static final int BYTE_LENGTH = 18;

    @Override
    public byte messageType() {
        return MESSAGE_TYPE;
    }

    public static OperationalHaltStatus decode(final byte[] buf, final int offset) {
        Bytes.requireLength(buf, offset, BYTE_LENGTH, "OperationalHaltStatus");
        Status status = Status.fromByte(buf[offset + 1]);
        long ts = Bytes.readLongLE(buf, offset + 2);
        String symbol = Bytes.decodeSymbol(buf, offset + 10);
        return new OperationalHaltStatus(status, ts, symbol);
    }

    public enum Status {
        IEX_HALTED((byte) 0x4f),   // 'O'
        NOT_HALTED((byte) 0x4e);   // 'N'

        public final byte value;

        Status(final byte value) {
            this.value = value;
        }

        public static Status fromByte(final byte b) {
            for (Status s : values()) {
                if (s.value == b) return s;
            }
            throw new IllegalArgumentException(
                    String.format("Unknown OperationalHaltStatus: 0x%02x", b & 0xff));
        }
    }
}
