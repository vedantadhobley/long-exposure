package com.longexposure.admin;

import com.longexposure.wire.Bytes;

/**
 * System Event Message — {@code S} (0x53), 10 bytes.
 *
 * <p>Lifecycle markers for the trading session: start/end of messages,
 * system hours, and regular market hours.
 *
 * <pre>
 * Offset  Size  Field
 *     0     1   Message Type (0x53)
 *     1     1   System Event identifier
 *     2     8   Timestamp (ns since POSIX epoch UTC)
 * </pre>
 */
public record SystemEvent(Event event, long timestampNanos) implements AdminMessage {

    public static final byte MESSAGE_TYPE = (byte) 0x53;
    public static final int BYTE_LENGTH = 10;

    @Override
    public byte messageType() {
        return MESSAGE_TYPE;
    }

    public static SystemEvent decode(final byte[] buf, final int offset) {
        Bytes.requireLength(buf, offset, BYTE_LENGTH, "SystemEvent");
        Event event = Event.fromByte(buf[offset + 1]);
        long ts = Bytes.readLongLE(buf, offset + 2);
        return new SystemEvent(event, ts);
    }

    public enum Event {
        START_OF_MESSAGES((byte) 0x4f),         // 'O' — outside heartbeats, first message of the session
        START_OF_SYSTEM_HOURS((byte) 0x53),     // 'S' — IEX accepting orders
        START_OF_REGULAR_MARKET_HOURS((byte) 0x52), // 'R'
        END_OF_REGULAR_MARKET_HOURS((byte) 0x4d),   // 'M'
        END_OF_SYSTEM_HOURS((byte) 0x45),       // 'E'
        END_OF_MESSAGES((byte) 0x43);           // 'C' — last message of the session

        public final byte value;

        Event(final byte value) {
            this.value = value;
        }

        public static Event fromByte(final byte b) {
            for (Event e : values()) {
                if (e.value == b) return e;
            }
            throw new IllegalArgumentException(
                    String.format("Unknown SystemEvent identifier: 0x%02x", b & 0xff));
        }
    }
}
