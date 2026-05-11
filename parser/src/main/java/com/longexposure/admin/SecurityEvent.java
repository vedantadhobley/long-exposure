package com.longexposure.admin;

/**
 * Security Event Message — {@code E} (0x45), 18 bytes.
 *
 * <p>Per-symbol Opening/Closing Process Complete marker. Carried by DEEP
 * and DEEP+; not present in TOPS, but byte-identical when it appears.
 *
 * <pre>
 * Offset  Size  Field
 *     0     1   Message Type (0x45)
 *     1     1   Security Event identifier (O or C)
 *     2     8   Timestamp
 *    10     8   Symbol
 * </pre>
 */
public record SecurityEvent(Event event, long timestampNanos, String symbol) implements AdminMessage {

    public static final byte MESSAGE_TYPE = (byte) 0x45;
    public static final int BYTE_LENGTH = 18;

    @Override
    public byte messageType() {
        return MESSAGE_TYPE;
    }

    public static SecurityEvent decode(final byte[] buf, final int offset) {
        Bytes.requireLength(buf, offset, BYTE_LENGTH, "SecurityEvent");
        Event event = Event.fromByte(buf[offset + 1]);
        long ts = Bytes.readLongLE(buf, offset + 2);
        String symbol = Bytes.decodeSymbol(buf, offset + 10);
        return new SecurityEvent(event, ts, symbol);
    }

    public enum Event {
        OPENING_PROCESS_COMPLETE((byte) 0x4f),   // 'O'
        CLOSING_PROCESS_COMPLETE((byte) 0x43);   // 'C'

        public final byte value;

        Event(final byte value) {
            this.value = value;
        }

        public static Event fromByte(final byte b) {
            for (Event e : values()) {
                if (e.value == b) return e;
            }
            throw new IllegalArgumentException(
                    String.format("Unknown SecurityEvent identifier: 0x%02x", b & 0xff));
        }
    }
}
