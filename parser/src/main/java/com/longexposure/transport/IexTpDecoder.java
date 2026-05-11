package com.longexposure.transport;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decoder for the 40-byte IEX Transport Protocol (IEX-TP) header that
 * prefaces every IEX market data UDP payload.
 *
 * Layout (all little-endian, per spec):
 * <pre>
 *  Offset  Size  Field
 *  ------  ----  --------------------------------
 *      0     1   Version
 *      1     1   Reserved
 *      2     2   Message Protocol ID  (TOPS=0x8003, DEEP=0x8004, DPLS=0x8005)
 *      4     4   Channel ID
 *      8     4   Session ID
 *     12     2   Payload Length
 *     14     2   Message Count
 *     16     8   Stream Offset
 *     24     8   First Sequence Number
 *     32     8   Send Time            (ns since POSIX epoch UTC)
 *  ------
 *     40 bytes total
 * </pre>
 */
public final class IexTpDecoder {

    public static final int HEADER_BYTES = 40;

    public static final int PROTOCOL_TOPS  = 0x8003;
    public static final int PROTOCOL_DEEP  = 0x8004;
    public static final int PROTOCOL_DEEPP = 0x8005;

    private IexTpDecoder() {}

    public static Header decode(final byte[] payload) {
        return decode(payload, 0);
    }

    public static Header decode(final byte[] payload, final int offset) {
        if (payload.length - offset < HEADER_BYTES) {
            throw new IllegalArgumentException("Payload too short for IEX-TP header: "
                    + (payload.length - offset) + " bytes available");
        }
        ByteBuffer b = ByteBuffer.wrap(payload, offset, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);

        byte version = b.get();
        byte reserved = b.get();
        int protocolId = b.getShort() & 0xffff;
        int channelId = b.getInt();
        int sessionId = b.getInt();
        int payloadLength = b.getShort() & 0xffff;
        int messageCount = b.getShort() & 0xffff;
        long streamOffset = b.getLong();
        long firstSequenceNumber = b.getLong();
        long sendTimeNanos = b.getLong();

        return new Header(
                version,
                reserved,
                protocolId,
                channelId,
                sessionId,
                payloadLength,
                messageCount,
                streamOffset,
                firstSequenceNumber,
                sendTimeNanos);
    }

    public record Header(
            byte version,
            byte reserved,
            int protocolId,
            int channelId,
            int sessionId,
            int payloadLength,
            int messageCount,
            long streamOffset,
            long firstSequenceNumber,
            long sendTimeNanos) {

        public String protocolName() {
            return switch (protocolId) {
                case PROTOCOL_TOPS  -> "TOPS";
                case PROTOCOL_DEEP  -> "DEEP";
                case PROTOCOL_DEEPP -> "DPLS";
                default -> String.format("UNKNOWN(0x%04x)", protocolId);
            };
        }
    }
}
