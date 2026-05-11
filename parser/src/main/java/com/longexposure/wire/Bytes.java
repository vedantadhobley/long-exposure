package com.longexposure.wire;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Wire-format decode helpers shared by every IEX message decoder.
 * IEX-TP and all message bodies are little-endian; strings are fixed-length
 * ASCII space-padded on the right.
 */
public final class Bytes {
    private Bytes() {}

    public static long readLongLE(final byte[] buf, final int offset) {
        return ByteBuffer.wrap(buf, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    public static int readIntLE(final byte[] buf, final int offset) {
        return ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    public static int readShortLE(final byte[] buf, final int offset) {
        return ByteBuffer.wrap(buf, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xffff;
    }

    /**
     * Decode an IEX-style fixed-length ASCII string: space-padded on the right.
     * Trailing 0x20 bytes are stripped.
     */
    public static String decodeFixedAscii(final byte[] buf, final int offset, final int length) {
        int end = offset + length;
        while (end > offset && buf[end - 1] == 0x20) {
            end--;
        }
        return new String(buf, offset, end - offset, StandardCharsets.US_ASCII);
    }

    /** Convenience for the IEX 8-byte Symbol field. */
    public static String decodeSymbol(final byte[] buf, final int offset) {
        return decodeFixedAscii(buf, offset, 8);
    }

    public static void requireLength(final byte[] buf, final int offset, final int required, final String what) {
        if (buf.length - offset < required) {
            throw new IllegalArgumentException(what + " requires " + required
                    + " bytes, only " + (buf.length - offset) + " available");
        }
    }
}
