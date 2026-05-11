package com.longexposure.transport;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IexTpDecoderTest {

    /**
     * Hand-built TOPS IEX-TP header — version 1, TOPS protocol, channel 1,
     * session 0x4b1881f0, payload of 35 bytes / 1 message, stream offset 937732,
     * first seq 44130, send time = 2022-08-17 07:28:10.893922241 UTC.
     * (Field values taken from the worked example on page 9 of the DEEP+ SNAP
     * spec, which embeds a complete IEX-TP header — the same wire format used
     * for TOPS and DEEP.)
     */
    @Test
    void decodesAllFieldsLittleEndian() {
        ByteBuffer b = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 0x01);                        // Version
        b.put((byte) 0x00);                        // Reserved
        b.putShort((short) 0x8005);                // Protocol ID (DEEP+)
        b.putInt(1);                               // Channel ID
        b.putInt(0x4b1881f0);                      // Session ID
        b.putShort((short) 35);                    // Payload length
        b.putShort((short) 1);                     // Message count
        b.putLong(937732L);                        // Stream offset
        b.putLong(44130L);                         // First sequence
        b.putLong(1660721290_893922241L);          // Send time (ns since epoch)

        IexTpDecoder.Header h = IexTpDecoder.decode(b.array());

        assertEquals((byte) 1, h.version());
        assertEquals((byte) 0, h.reserved());
        assertEquals(0x8005, h.protocolId());
        assertEquals("DEEP+", h.protocolName());
        assertEquals(1, h.channelId());
        assertEquals(0x4b1881f0, h.sessionId());
        assertEquals(35, h.payloadLength());
        assertEquals(1, h.messageCount());
        assertEquals(937732L, h.streamOffset());
        assertEquals(44130L, h.firstSequenceNumber());
        assertEquals(1660721290_893922241L, h.sendTimeNanos());
    }

    @Test
    void recognizesProtocolIdsForAllFeeds() {
        assertEquals("TOPS",  buildHeaderWithProtocol(0x8003).protocolName());
        assertEquals("DEEP",  buildHeaderWithProtocol(0x8004).protocolName());
        assertEquals("DEEP+", buildHeaderWithProtocol(0x8005).protocolName());
    }

    @Test
    void unknownProtocolIdSurfacesInName() {
        IexTpDecoder.Header h = buildHeaderWithProtocol(0x1234);
        assertEquals("UNKNOWN(0x1234)", h.protocolName());
    }

    @Test
    void throwsOnShortPayload() {
        assertThrows(IllegalArgumentException.class,
                () -> IexTpDecoder.decode(new byte[39]));
    }

    @Test
    void decodesAtOffset() {
        byte[] buf = new byte[100];
        ByteBuffer b = ByteBuffer.wrap(buf, 20, 40).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 0x01).put((byte) 0x00).putShort((short) 0x8003);
        b.putInt(1).putInt(42).putShort((short) 0).putShort((short) 0);
        b.putLong(0L).putLong(99L).putLong(0L);

        IexTpDecoder.Header h = IexTpDecoder.decode(buf, 20);
        assertEquals("TOPS", h.protocolName());
        assertEquals(42, h.sessionId());
        assertEquals(99L, h.firstSequenceNumber());
    }

    private static IexTpDecoder.Header buildHeaderWithProtocol(final int protocolId) {
        ByteBuffer b = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 1).put((byte) 0).putShort((short) protocolId);
        b.putInt(1).putInt(0).putShort((short) 0).putShort((short) 0);
        b.putLong(0L).putLong(0L).putLong(0L);
        return IexTpDecoder.decode(b.array());
    }
}
