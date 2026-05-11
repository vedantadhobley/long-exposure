package com.longexposure.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-level decoder tests for the 7 admin message types. Each test case
 * is transcribed from the worked example block in the TOPS 1.66 / DEEP 1.08
 * spec PDFs (pages cited inline).
 */
class AdminMessagesTest {

    // Shared timestamp bytes used in several TOPS spec examples
    // (TradingStatus / RetailLiquidityIndicator / OperationalHaltStatus /
    // ShortSalePriceTestStatus). The spec annotation for these bytes claims
    // "2016-08-23 15:30:32.572715948" — but that's actually Eastern Time,
    // because the bytes decode to 2016-08-23 19:30:32.572715948 *UTC*
    // (= 15:30:32 EDT, UTC−4 in August). The spec elsewhere states
    // timestamps are UTC; this example annotation is inconsistent.
    // We assert on the actual decoded UTC value.
    private static final long TS_2016_08_23_UTC = 1471980632_572715948L;
    private static final byte[] TS_2016_08_23_BYTES =
            { (byte) 0xac, 0x63, (byte) 0xc0, 0x20, (byte) 0x96, (byte) 0x86, 0x6d, 0x14 };

    // ZIEXT padded to 8 ASCII bytes
    private static final byte[] ZIEXT_BYTES =
            { 0x5a, 0x49, 0x45, 0x58, 0x54, 0x20, 0x20, 0x20 };

    // ─── SystemEvent (TOPS 1.66 spec, page 7) ────────────────────────────────

    @Test
    void decodesSystemEvent() {
        // 00 a0 99 97 e9 3d b6 14  →  2017-04-17 17:00:00.000000000 UTC
        byte[] buf = bytes(
                0x53,                                   // Message Type = S
                0x45,                                   // E = End of System Hours
                0x00, 0xa0, 0x99, 0x97, 0xe9, 0x3d, 0xb6, 0x14);

        SystemEvent m = SystemEvent.decode(buf, 0);

        assertEquals(SystemEvent.Event.END_OF_SYSTEM_HOURS, m.event());
        assertEquals(1492448400_000000000L, m.timestampNanos());
        assertEquals((byte) 0x53, m.messageType());
    }

    // ─── SecurityDirectory (TOPS 1.66 spec, page 9) ──────────────────────────

    @Test
    void decodesSecurityDirectory() {
        // 00 20 89 7b 5a 1f b6 14  →  2017-04-17 07:40:00.000000000 UTC
        // 24 1d 0f 00 00 00 00 00  →  $99.05 (raw 990500)
        byte[] buf = bytes(
                0x44,                                                  // Type = D
                0x80,                                                  // Flags: test sec, not ETP, not WI
                0x00, 0x20, 0x89, 0x7b, 0x5a, 0x1f, 0xb6, 0x14,      // Timestamp
                0x5a, 0x49, 0x45, 0x58, 0x54, 0x20, 0x20, 0x20,      // Symbol = ZIEXT
                0x64, 0x00, 0x00, 0x00,                              // Round Lot = 100
                0x24, 0x1d, 0x0f, 0x00, 0x00, 0x00, 0x00, 0x00,      // Adj POC = $99.05
                0x01);                                               // LULD Tier 1

        SecurityDirectory m = SecurityDirectory.decode(buf, 0);

        assertEquals("ZIEXT", m.symbol());
        assertEquals(100, m.roundLotSize());
        assertEquals(990500L, m.adjustedPocPriceRaw());
        assertEquals(99.05, m.adjustedPocPrice(), 1e-9);
        assertEquals(SecurityDirectory.LuldTier.TIER_1, m.luldTier());
        assertTrue(m.isTestSecurity());
        assertFalse(m.isEtp());
        assertFalse(m.isWhenIssued());
        assertEquals(1492414800_000000000L, m.timestampNanos());
    }

    // ─── TradingStatus (TOPS 1.66 spec, page 11) ─────────────────────────────

    @Test
    void decodesTradingStatusHalt() {
        byte[] buf = concat(
                bytes(0x48,                       // Type = H
                      0x48),                      // Status = H (Halted)
                TS_2016_08_23_BYTES,
                ZIEXT_BYTES,
                bytes(0x54, 0x31, 0x20, 0x20));   // Reason = "T1"

        TradingStatus m = TradingStatus.decode(buf, 0);

        assertEquals(TradingStatus.Status.HALTED, m.status());
        assertEquals("ZIEXT", m.symbol());
        assertEquals("T1", m.reason());
        assertEquals(TS_2016_08_23_UTC, m.timestampNanos());
    }

    @Test
    void tradingStatusReasonIsBlankWhenTrading() {
        byte[] buf = concat(
                bytes(0x48,
                      0x54),                      // T = Trading
                TS_2016_08_23_BYTES,
                ZIEXT_BYTES,
                bytes(0x20, 0x20, 0x20, 0x20));   // Reason = all spaces → ""

        TradingStatus m = TradingStatus.decode(buf, 0);
        assertEquals(TradingStatus.Status.TRADING, m.status());
        assertEquals("", m.reason());
    }

    // ─── RetailLiquidityIndicator (TOPS 1.66 spec, page 12) ──────────────────

    @Test
    void decodesRetailLiquidityIndicator() {
        byte[] buf = concat(
                bytes(0x49,
                      0x41),                      // A = Buy interest
                TS_2016_08_23_BYTES,
                ZIEXT_BYTES);

        RetailLiquidityIndicator m = RetailLiquidityIndicator.decode(buf, 0);
        assertEquals(RetailLiquidityIndicator.Indicator.BUY, m.indicator());
        assertEquals("ZIEXT", m.symbol());
        assertEquals(TS_2016_08_23_UTC, m.timestampNanos());
    }

    // ─── OperationalHaltStatus (TOPS 1.66 spec, page 13) ─────────────────────

    @Test
    void decodesOperationalHaltStatus() {
        byte[] buf = concat(
                bytes(0x4f,
                      0x4f),                      // O = IEX-specific operational halt
                TS_2016_08_23_BYTES,
                ZIEXT_BYTES);

        OperationalHaltStatus m = OperationalHaltStatus.decode(buf, 0);
        assertEquals(OperationalHaltStatus.Status.IEX_HALTED, m.status());
        assertEquals("ZIEXT", m.symbol());
    }

    // ─── ShortSalePriceTestStatus (TOPS 1.66 spec, page 14–15) ───────────────

    @Test
    void decodesShortSalePriceTestStatus() {
        byte[] buf = concat(
                bytes(0x50,
                      0x01),                      // In effect
                TS_2016_08_23_BYTES,
                ZIEXT_BYTES,
                bytes(0x41));                     // Detail = A (Activated)

        ShortSalePriceTestStatus m = ShortSalePriceTestStatus.decode(buf, 0);
        assertEquals(ShortSalePriceTestStatus.Status.IN_EFFECT, m.status());
        assertEquals(ShortSalePriceTestStatus.Detail.ACTIVATED, m.detail());
        assertEquals("ZIEXT", m.symbol());
    }

    // ─── SecurityEvent (DEEP 1.08 spec, page 16) ─────────────────────────────

    @Test
    void decodesSecurityEvent() {
        // 00 f0 30 2a 5b 25 b6 14  →  2017-04-17 09:30:00.000000000 UTC
        byte[] buf = concat(
                bytes(0x45,
                      0x4f),                      // O = Opening Process Complete
                bytes(0x00, 0xf0, 0x30, 0x2a, 0x5b, 0x25, 0xb6, 0x14),
                ZIEXT_BYTES);

        SecurityEvent m = SecurityEvent.decode(buf, 0);
        assertEquals(SecurityEvent.Event.OPENING_PROCESS_COMPLETE, m.event());
        assertEquals("ZIEXT", m.symbol());
        assertEquals(1492421400_000000000L, m.timestampNanos());
    }

    // ─── Dispatcher ──────────────────────────────────────────────────────────

    @Test
    void dispatcherRoutesEveryAdminType() {
        // SystemEvent
        byte[] s = bytes(0x53, 0x45, 0x00, 0xa0, 0x99, 0x97, 0xe9, 0x3d, 0xb6, 0x14);
        assertEquals(SystemEvent.class, AdminMessages.decode((byte) 0x53, s, 0).getClass());

        // SecurityEvent
        byte[] e = concat(bytes(0x45, 0x4f),
                bytes(0x00, 0xf0, 0x30, 0x2a, 0x5b, 0x25, 0xb6, 0x14),
                ZIEXT_BYTES);
        assertEquals(SecurityEvent.class, AdminMessages.decode((byte) 0x45, e, 0).getClass());

        // OperationalHaltStatus
        byte[] o = concat(bytes(0x4f, 0x4f), TS_2016_08_23_BYTES, ZIEXT_BYTES);
        assertEquals(OperationalHaltStatus.class, AdminMessages.decode((byte) 0x4f, o, 0).getClass());

        // RetailLiquidityIndicator
        byte[] i = concat(bytes(0x49, 0x41), TS_2016_08_23_BYTES, ZIEXT_BYTES);
        assertEquals(RetailLiquidityIndicator.class, AdminMessages.decode((byte) 0x49, i, 0).getClass());
    }

    @Test
    void dispatcherReturnsNullForUnknownTypes() {
        // 0x51 = Quote Update — a TOPS trading type, not admin.
        assertNull(AdminMessages.decode((byte) 0x51, new byte[100], 0));
    }

    @Test
    void isAdminTypeMatchesEveryAdminConstant() {
        assertTrue(AdminMessages.isAdminType(SystemEvent.MESSAGE_TYPE));
        assertTrue(AdminMessages.isAdminType(SecurityDirectory.MESSAGE_TYPE));
        assertTrue(AdminMessages.isAdminType(TradingStatus.MESSAGE_TYPE));
        assertTrue(AdminMessages.isAdminType(RetailLiquidityIndicator.MESSAGE_TYPE));
        assertTrue(AdminMessages.isAdminType(OperationalHaltStatus.MESSAGE_TYPE));
        assertTrue(AdminMessages.isAdminType(ShortSalePriceTestStatus.MESSAGE_TYPE));
        assertTrue(AdminMessages.isAdminType(SecurityEvent.MESSAGE_TYPE));
        assertFalse(AdminMessages.isAdminType((byte) 0x51));  // Q
        assertFalse(AdminMessages.isAdminType((byte) 0x54));  // T
    }

    // ─── Error handling ──────────────────────────────────────────────────────

    @Test
    void shortBufferThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> SystemEvent.decode(new byte[9], 0));
        assertThrows(IllegalArgumentException.class,
                () -> SecurityDirectory.decode(new byte[30], 0));
    }

    @Test
    void unknownEnumValueThrows() {
        byte[] buf = bytes(0x53, 0x7f, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> SystemEvent.decode(buf, 0));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    /** Build a byte array from int literals (avoids casting every byte). */
    private static byte[] bytes(final int... vals) {
        byte[] out = new byte[vals.length];
        for (int i = 0; i < vals.length; i++) {
            out[i] = (byte) (vals[i] & 0xff);
        }
        return out;
    }

    private static byte[] concat(final byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, pos, a.length);
            pos += a.length;
        }
        return out;
    }
}
