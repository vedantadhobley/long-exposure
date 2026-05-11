package com.longexposure.admin;

import com.longexposure.wire.Bytes;

/**
 * Trading Status Message — {@code H} (0x48), 22 bytes.
 *
 * <p>Per-symbol trading state: halted, paused, OAP, or trading. For halt
 * and OAP states a 4-byte Reason code is populated (T1, IPO1, MCB3, etc.);
 * for trading or pause it's blank.
 *
 * <pre>
 * Offset  Size  Field
 *     0     1   Message Type (0x48)
 *     1     1   Trading Status (H / O / P / T)
 *     2     8   Timestamp
 *    10     8   Symbol
 *    18     4   Reason  (String — blank for non-halt states)
 * </pre>
 */
public record TradingStatus(Status status, long timestampNanos, String symbol, String reason)
        implements AdminMessage {

    public static final byte MESSAGE_TYPE = (byte) 0x48;
    public static final int BYTE_LENGTH = 22;

    @Override
    public byte messageType() {
        return MESSAGE_TYPE;
    }

    public static TradingStatus decode(final byte[] buf, final int offset) {
        Bytes.requireLength(buf, offset, BYTE_LENGTH, "TradingStatus");
        Status status = Status.fromByte(buf[offset + 1]);
        long ts = Bytes.readLongLE(buf, offset + 2);
        String symbol = Bytes.decodeSymbol(buf, offset + 10);
        String reason = Bytes.decodeFixedAscii(buf, offset + 18, 4);
        return new TradingStatus(status, ts, symbol, reason);
    }

    public enum Status {
        HALTED((byte) 0x48),                  // 'H' — across all US equity markets
        ORDER_ACCEPTANCE_PERIOD((byte) 0x4f), // 'O' — halt released into OAP, IEX-listed
        PAUSED((byte) 0x50),                  // 'P' — paused + OAP, IEX-listed
        TRADING((byte) 0x54);                 // 'T'

        public final byte value;

        Status(final byte value) {
            this.value = value;
        }

        public static Status fromByte(final byte b) {
            for (Status s : values()) {
                if (s.value == b) return s;
            }
            throw new IllegalArgumentException(
                    String.format("Unknown TradingStatus: 0x%02x", b & 0xff));
        }
    }
}
