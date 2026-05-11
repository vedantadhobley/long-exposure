package com.longexposure.admin;

/**
 * Security Directory Message — {@code D} (0x44), 31 bytes.
 *
 * <p>Per-symbol metadata: round lot size, corporate-action-adjusted previous
 * official close (used as the day's reference price), LULD tier, and flags
 * indicating test security / ETP / when-issued.
 *
 * <p>IEX disseminates a full pre-market spin of these for all IEX-listed
 * securities at session start, then relays updates intraday as metadata
 * changes.
 *
 * <pre>
 * Offset  Size  Field
 *     0     1   Message Type (0x44)
 *     1     1   Flags (bitfield — see {@link #isTestSecurity} et al.)
 *     2     8   Timestamp
 *    10     8   Symbol
 *    18     4   Round Lot Size  (Integer, unsigned)
 *    22     8   Adjusted POC Price  (Price — 8B signed int, 4 implied decimals)
 *    30     1   LULD Tier
 * </pre>
 */
public record SecurityDirectory(
        byte flags,
        long timestampNanos,
        String symbol,
        int roundLotSize,
        long adjustedPocPriceRaw,
        LuldTier luldTier) implements AdminMessage {

    public static final byte MESSAGE_TYPE = (byte) 0x44;
    public static final int BYTE_LENGTH = 31;

    // Flag bits (per spec Appendix A). The spec uses bit 7 (0x80) for the
    // test-security flag, bit 6 (0x40) for ETP, bit 5 (0x20) for When Issued.
    private static final int FLAG_TEST_SECURITY = 0x80;
    private static final int FLAG_ETP           = 0x40;
    private static final int FLAG_WHEN_ISSUED   = 0x20;

    @Override
    public byte messageType() {
        return MESSAGE_TYPE;
    }

    public static SecurityDirectory decode(final byte[] buf, final int offset) {
        Bytes.requireLength(buf, offset, BYTE_LENGTH, "SecurityDirectory");
        byte flags = buf[offset + 1];
        long ts = Bytes.readLongLE(buf, offset + 2);
        String symbol = Bytes.decodeSymbol(buf, offset + 10);
        int roundLot = Bytes.readIntLE(buf, offset + 18);
        long pocRaw = Bytes.readLongLE(buf, offset + 22);
        LuldTier tier = LuldTier.fromByte(buf[offset + 30]);
        return new SecurityDirectory(flags, ts, symbol, roundLot, pocRaw, tier);
    }

    public boolean isTestSecurity() {
        return (flags & FLAG_TEST_SECURITY) != 0;
    }

    public boolean isEtp() {
        return (flags & FLAG_ETP) != 0;
    }

    public boolean isWhenIssued() {
        return (flags & FLAG_WHEN_ISSUED) != 0;
    }

    /** Adjusted POC price as a decimal. Storage keeps the raw long; this is for display. */
    public double adjustedPocPrice() {
        return adjustedPocPriceRaw / 10_000.0;
    }

    public enum LuldTier {
        NOT_APPLICABLE((byte) 0x00),
        TIER_1((byte) 0x01),
        TIER_2((byte) 0x02);

        public final byte value;

        LuldTier(final byte value) {
            this.value = value;
        }

        public static LuldTier fromByte(final byte b) {
            for (LuldTier t : values()) {
                if (t.value == b) return t;
            }
            throw new IllegalArgumentException(
                    String.format("Unknown LULD Tier: 0x%02x", b & 0xff));
        }
    }
}
