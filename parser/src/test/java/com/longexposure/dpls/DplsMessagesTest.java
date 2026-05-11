package com.longexposure.dpls;

import com.longexposure.admin.SystemEvent;
import com.longexposure.admin.TradingStatus;
import com.longexposure.wire.IexMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-level decoder tests for the 7 DPLS trading messages plus the
 * DplsMessageRouter. Worked example bytes are transcribed from the
 * DPLS 1.02 spec PDF (pages 16-22 for individual messages; appendix B
 * bitwise representations on pages 32-38 confirm the offsets).
 *
 * <p>Note on timestamp annotations: DPLS spec examples reuse the same
 * "2016-08-23 15:32:04.912754610" timestamp byte sequence that the TOPS
 * spec uses. We verified earlier (see TopsMessagesTest + protocol-notes
 * gotcha) that this annotation is in Eastern Time despite the spec text
 * saying timestamps are UTC. Bytes decode to 19:32:04 UTC.
 */
class DplsMessagesTest {

    private static final byte[] ZIEXT =
            { 0x5a, 0x49, 0x45, 0x58, 0x54, 0x20, 0x20, 0x20 };

    // Shared timestamp bytes used in DPLS spec examples.
    // Decodes to 2016-08-23 19:32:04.912754610 UTC (spec annotation is 15:32:04 ET).
    private static final byte[] TS_BYTES =
            { (byte) 0xb2, (byte) 0x8f, (byte) 0xa5, (byte) 0xa0,
              (byte) 0xab, (byte) 0x86, 0x6d, 0x14 };
    private static final long TS_UTC = 1471980724_912754610L;

    // $99.05 in IEX 4-decimal-implied price encoding (990500 raw)
    private static final byte[] PRICE_99_05 = bytes(0x24, 0x1d, 0x0f, 0x00, 0x00, 0x00, 0x00, 0x00);

    // Order ID 429974 (96 8f 06 00 00 00 00 00 LE)
    private static final byte[] ORDER_ID_429974 = bytes(0x96, 0x8f, 0x06, 0x00, 0x00, 0x00, 0x00, 0x00);
    private static final long ORDER_ID_429974_VAL = 429974L;

    // Trade ID 167830 (96 8f 02 00 00 00 00 00 LE)
    private static final byte[] TRADE_ID_167830 = bytes(0x96, 0x8f, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00);
    private static final long TRADE_ID_167830_VAL = 167830L;

    // Size 100 (64 00 00 00 LE)
    private static final byte[] SIZE_100 = bytes(0x64, 0x00, 0x00, 0x00);

    // ─── AddOrder (a, 0x61, 38B) ─────────────────────────────────────────────

    @Test
    void decodesAddOrder() {
        byte[] buf = concat(
                bytes(0x61,                                  // Type = a
                      0x38),                                 // Side = '8' (Buy)
                TS_BYTES,
                ZIEXT,
                ORDER_ID_429974,
                SIZE_100,
                PRICE_99_05);

        AddOrder m = AddOrder.decode(buf, 0);

        assertEquals(AddOrder.Side.BUY, m.side());
        assertEquals("ZIEXT", m.symbol());
        assertEquals(ORDER_ID_429974_VAL, m.orderId());
        assertEquals(100, m.size());
        assertEquals(990500L, m.priceRaw());
        assertEquals(99.05, m.price(), 1e-9);
        assertEquals(TS_UTC, m.timestampNanos());
        assertEquals(AddOrder.MESSAGE_TYPE, m.messageType());
    }

    @Test
    void decodesAddOrderSellSide() {
        byte[] buf = concat(
                bytes(0x61, 0x35),                            // Side = '5' (Sell)
                TS_BYTES, ZIEXT, ORDER_ID_429974, SIZE_100, PRICE_99_05);
        assertEquals(AddOrder.Side.SELL, AddOrder.decode(buf, 0).side());
    }

    // ─── OrderModify (M, 0x4D, 38B) ──────────────────────────────────────────

    @Test
    void decodesOrderModifyResetPriority() {
        byte[] buf = concat(
                bytes(0x4D,                                  // Type = M
                      0x00),                                 // Modify Flags = 0 (reset priority)
                TS_BYTES,
                ZIEXT,
                ORDER_ID_429974,
                SIZE_100,
                PRICE_99_05);

        OrderModify m = OrderModify.decode(buf, 0);

        assertEquals(ORDER_ID_429974_VAL, m.orderId());
        assertEquals(100, m.size());
        assertEquals(990500L, m.priceRaw());
        assertEquals("ZIEXT", m.symbol());
        assertFalse(m.maintainPriority());
        assertEquals(TS_UTC, m.timestampNanos());
    }

    @Test
    void decodesOrderModifyMaintainPriority() {
        byte[] buf = concat(
                bytes(0x4D, 0x01),                            // Modify Flags = bit 0 set
                TS_BYTES, ZIEXT, ORDER_ID_429974, SIZE_100, PRICE_99_05);
        assertTrue(OrderModify.decode(buf, 0).maintainPriority());
    }

    // ─── OrderDelete (R, 0x52, 26B) ──────────────────────────────────────────

    @Test
    void decodesOrderDelete() {
        byte[] buf = concat(
                bytes(0x52,                                  // Type = R
                      0x00),                                 // Reserved
                TS_BYTES,
                ZIEXT,
                ORDER_ID_429974);

        OrderDelete m = OrderDelete.decode(buf, 0);

        assertEquals(ORDER_ID_429974_VAL, m.orderId());
        assertEquals("ZIEXT", m.symbol());
        assertEquals(TS_UTC, m.timestampNanos());
        assertEquals(OrderDelete.MESSAGE_TYPE, m.messageType());
    }

    // ─── OrderExecuted (L, 0x4C, 46B) ────────────────────────────────────────

    @Test
    void decodesOrderExecuted() {
        byte[] buf = concat(
                bytes(0x4C,                                  // Type = L
                      0x00),                                 // Sale Condition Flags = 0
                TS_BYTES,
                ZIEXT,
                ORDER_ID_429974,
                SIZE_100,
                PRICE_99_05,
                TRADE_ID_167830);

        OrderExecuted m = OrderExecuted.decode(buf, 0);

        assertEquals(ORDER_ID_429974_VAL, m.orderId());
        assertEquals(TRADE_ID_167830_VAL, m.tradeId());
        assertEquals(100, m.size());
        assertEquals(990500L, m.priceRaw());
        assertEquals("ZIEXT", m.symbol());
        assertEquals(TS_UTC, m.timestampNanos());
        assertFalse(m.conditions().isExtendedHours());
        assertTrue(m.conditions().isLastSaleEligible());
    }

    // ─── Trade (T, 0x54, 38B) ────────────────────────────────────────────────

    @Test
    void decodesTrade() {
        byte[] buf = concat(
                bytes(0x54,                                  // Type = T
                      0x00),                                 // Sale Condition Flags = 0
                TS_BYTES,
                ZIEXT,
                SIZE_100,
                PRICE_99_05,
                TRADE_ID_167830);

        Trade m = Trade.decode(buf, 0);

        assertEquals("ZIEXT", m.symbol());
        assertEquals(100, m.size());
        assertEquals(990500L, m.priceRaw());
        assertEquals(TRADE_ID_167830_VAL, m.tradeId());
        assertEquals(TS_UTC, m.timestampNanos());
    }

    // ─── TradeBreak (B, 0x42, 38B) ───────────────────────────────────────────

    @Test
    void decodesTradeBreak() {
        // Spec example uses Order ID 429974 as the broken Trade ID
        byte[] buf = concat(
                bytes(0x42,                                  // Type = B
                      0x00),                                 // Sale Condition Flags = 0
                TS_BYTES,
                ZIEXT,
                SIZE_100,
                PRICE_99_05,
                ORDER_ID_429974);                             // Trade ID = 429974 (the broken trade)

        TradeBreak m = TradeBreak.decode(buf, 0);

        assertEquals(ORDER_ID_429974_VAL, m.brokenTradeId());
        assertEquals(100, m.size());
        assertEquals(990500L, m.priceRaw());
        assertEquals("ZIEXT", m.symbol());
        assertEquals(TS_UTC, m.timestampNanos());
    }

    // ─── ClearBook (C, 0x43, 18B) ────────────────────────────────────────────

    @Test
    void decodesClearBook() {
        byte[] buf = concat(
                bytes(0x43,                                  // Type = C
                      0x00),                                 // Reserved
                TS_BYTES,
                ZIEXT);

        ClearBook m = ClearBook.decode(buf, 0);

        assertEquals("ZIEXT", m.symbol());
        assertEquals(TS_UTC, m.timestampNanos());
        assertEquals(ClearBook.MESSAGE_TYPE, m.messageType());
    }

    // ─── Router ──────────────────────────────────────────────────────────────

    @Test
    void routerDispatchesAdminTypeToAdminDecoder() {
        // 'S' SystemEvent — admin, byte-identical across all feeds
        byte[] buf = bytes(0x53, 0x45, 0x00, 0xa0, 0x99, 0x97, 0xe9, 0x3d, 0xb6, 0x14);
        IexMessage m = DplsMessageRouter.decode((byte) 0x53, buf, 0);
        assertInstanceOf(SystemEvent.class, m);
    }

    @Test
    void routerDispatchesEachDplsTradingType() {
        // a — Add Order
        byte[] a = concat(bytes(0x61, 0x38), TS_BYTES, ZIEXT, ORDER_ID_429974, SIZE_100, PRICE_99_05);
        assertInstanceOf(AddOrder.class, DplsMessageRouter.decode((byte) 0x61, a, 0));

        // M — Order Modify
        byte[] mod = concat(bytes(0x4D, 0x00), TS_BYTES, ZIEXT, ORDER_ID_429974, SIZE_100, PRICE_99_05);
        assertInstanceOf(OrderModify.class, DplsMessageRouter.decode((byte) 0x4D, mod, 0));

        // R — Order Delete
        byte[] del = concat(bytes(0x52, 0x00), TS_BYTES, ZIEXT, ORDER_ID_429974);
        assertInstanceOf(OrderDelete.class, DplsMessageRouter.decode((byte) 0x52, del, 0));

        // L — Order Executed
        byte[] ex = concat(bytes(0x4C, 0x00), TS_BYTES, ZIEXT, ORDER_ID_429974, SIZE_100, PRICE_99_05, TRADE_ID_167830);
        assertInstanceOf(OrderExecuted.class, DplsMessageRouter.decode((byte) 0x4C, ex, 0));

        // T — Trade
        byte[] t = concat(bytes(0x54, 0x00), TS_BYTES, ZIEXT, SIZE_100, PRICE_99_05, TRADE_ID_167830);
        assertInstanceOf(Trade.class, DplsMessageRouter.decode((byte) 0x54, t, 0));

        // B — Trade Break
        byte[] tb = concat(bytes(0x42, 0x00), TS_BYTES, ZIEXT, SIZE_100, PRICE_99_05, ORDER_ID_429974);
        assertInstanceOf(TradeBreak.class, DplsMessageRouter.decode((byte) 0x42, tb, 0));

        // C — Clear Book
        byte[] cb = concat(bytes(0x43, 0x00), TS_BYTES, ZIEXT);
        assertInstanceOf(ClearBook.class, DplsMessageRouter.decode((byte) 0x43, cb, 0));
    }

    @Test
    void routerHandlesAllSevenDplsTradingTypes() {
        assertTrue(DplsMessageRouter.isDplsTradingType(AddOrder.MESSAGE_TYPE));
        assertTrue(DplsMessageRouter.isDplsTradingType(OrderModify.MESSAGE_TYPE));
        assertTrue(DplsMessageRouter.isDplsTradingType(OrderDelete.MESSAGE_TYPE));
        assertTrue(DplsMessageRouter.isDplsTradingType(OrderExecuted.MESSAGE_TYPE));
        assertTrue(DplsMessageRouter.isDplsTradingType(Trade.MESSAGE_TYPE));
        assertTrue(DplsMessageRouter.isDplsTradingType(TradeBreak.MESSAGE_TYPE));
        assertTrue(DplsMessageRouter.isDplsTradingType(ClearBook.MESSAGE_TYPE));
        assertFalse(DplsMessageRouter.isDplsTradingType(SystemEvent.MESSAGE_TYPE));
        assertFalse(DplsMessageRouter.isDplsTradingType(TradingStatus.MESSAGE_TYPE));
        // 0x51 = TOPS Quote Update — not a DPLS trading type
        assertFalse(DplsMessageRouter.isDplsTradingType((byte) 0x51));
    }

    @Test
    void routerThrowsOnUnknownType() {
        // 0x51 = TOPS Quote Update — would never appear in a DPLS stream
        assertThrows(IllegalArgumentException.class,
                () -> DplsMessageRouter.decode((byte) 0x51, new byte[100], 0));
    }

    @Test
    void shortBufferThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> AddOrder.decode(new byte[37], 0));
        assertThrows(IllegalArgumentException.class,
                () -> OrderExecuted.decode(new byte[45], 0));
    }

    @Test
    void unknownSideThrows() {
        byte[] buf = concat(bytes(0x61, 0x7f), TS_BYTES, ZIEXT, ORDER_ID_429974, SIZE_100, PRICE_99_05);
        assertThrows(IllegalArgumentException.class, () -> AddOrder.decode(buf, 0));
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

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
