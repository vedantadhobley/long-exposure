package com.longexposure.admin;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Little-endian + IEX-string decode helpers. Package-private; admin records
 * call these from their static {@code decode} methods.
 */
final class Bytes {
    private Bytes() {}

    static long readLongLE(final byte[] buf, final int offset) {
        return ByteBuffer.wrap(buf, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    static int readIntLE(final byte[] buf, final int offset) {
        return ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /**
     * Decode an IEX-style fixed-length ASCII string: space-padded on the right.
     * Trailing 0x20 bytes are stripped from the returned String.
     */
    static String decodeFixedAscii(final byte[] buf, final int offset, final int length) {
        int end = offset + length;
        while (end > offset && buf[end - 1] == 0x20) {
            end--;
        }
        return new String(buf, offset, end - offset, StandardCharsets.US_ASCII);
    }

    /** Convenience for the IEX 8-byte Symbol field. */
    static String decodeSymbol(final byte[] buf, final int offset) {
        return decodeFixedAscii(buf, offset, 8);
    }

    static void requireLength(final byte[] buf, final int offset, final int required, final String what) {
        if (buf.length - offset < required) {
            throw new IllegalArgumentException(what + " requires " + required
                    + " bytes, only " + (buf.length - offset) + " available");
        }
    }
}
