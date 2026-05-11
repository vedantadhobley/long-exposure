package com.longexposure.deep;

import com.longexposure.tops.AuctionInformation;
import com.longexposure.tops.OfficialPrice;
import com.longexposure.tops.TradeBreak;
import com.longexposure.tops.TradeReport;
import com.longexposure.wire.IexMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decoder tests for DEEP-specific and DEEP-routed messages. The
 * DEEP-unique surface is just {@link PriceLevelUpdate}; the router also
 * dispatches the four byte-identical-to-TOPS trading types ({@code T},
 * {@code B}, {@code X}, {@code A}) and the seven byte-identical admin
 * types — the router-level tests below confirm we get the right typed
 * record back for each.
 *
 * <p>Worked-example bytes come from the DEEP v1.08 spec (PriceLevelUpdate
 * pages ~15–17) and reuse the symbol / timestamp / price helpers from
 * the TOPS + DPLS test files for cross-feed comparability.
 */
class DeepMessagesTest {

    private static final byte[] ZIEXT =
            { 0x5a, 0x49, 0x45, 0x58, 0x54, 0x20, 0x20, 0x20 };

    // Shared timestamp bytes used across IEX spec examples.
    // Decodes to 2016-08-23 19:32:04.912754610 UTC.
    private static final byte[] TS_BYTES =
            { (byte) 0xb2, (byte) 0x8f, (byte) 0xa5, (byte) 0xa0,
              (byte) 0xab, (byte) 0x86, 0x6d, 0x14 };
    private static final long TS_UTC = 1471980724_912754610L;

    // $99.05 (990500 raw)
    private static final byte[] PRICE_99_05 = bytes(0x24, 0x1d, 0x0f, 0x00, 0x00, 0x00, 0x00, 0x00);

    // Size 9700 (LE: e4 25 00 00 — a more spec-typical PLU size than 100)
    private static final byte[] SIZE_9700 = bytes(0xe4, 0x25, 0x00, 0x00);
    private static final int SIZE_9700_VAL = 9700;

    // ─── PriceLevelUpdate Buy (8, 0x38, 30B) ─────────────────────────────────

    @Test
    void decodesBuyPriceLevelUpdateTransactionComplete() {
        byte[] buf = concat(
                bytes(0x38,                  // Type = '8' (Buy PLU)
                      0x01),                 // Event Flags = 1 (transaction complete)
                TS_BYTES,
                ZIEXT,
                SIZE_9700,
                PRICE_99_05);

        PriceLevelUpdate m = PriceLevelUpdate.decode(PriceLevelUpdate.Side.BUY, buf, 0);

        assertEquals(PriceLevelUpdate.Side.BUY, m.side());
        assertEquals(PriceLevelUpdate.MESSAGE_TYPE_BUY, m.messageType());
        assertEquals(SIZE_9700_VAL, m.size());
        assertEquals(990500L, m.priceRaw());
        assertEquals(99.05, m.price(), 1e-9);
        assertEquals("ZIEXT", m.symbol());
        assertEquals(TS_UTC, m.timestampNanos());
        assertTrue(m.isTransactionComplete());
    }

    @Test
    void decodesSellPriceLevelUpdateMidTransaction() {
        byte[] buf = concat(
                bytes(0x35,                  // Type = '5' (Sell PLU)
                      0x00),                 // Event Flags = 0 (mid-transaction)
                TS_BYTES, ZIEXT, SIZE_9700, PRICE_99_05);

        PriceLevelUpdate m = PriceLevelUpdate.decode(PriceLevelUpdate.Side.SELL, buf, 0);

        assertEquals(PriceLevelUpdate.Side.SELL, m.side());
        assertEquals(PriceLevelUpdate.MESSAGE_TYPE_SELL, m.messageType());
        assertFalse(m.isTransactionComplete());
    }

    @Test
    void priceLevelUpdateSizeZeroMeansLevelRemoved() {
        byte[] buf = concat(
                bytes(0x38, 0x01),
                TS_BYTES,
                ZIEXT,
                bytes(0x00, 0x00, 0x00, 0x00),    // Size = 0
                PRICE_99_05);

        PriceLevelUpdate m = PriceLevelUpdate.decode(PriceLevelUpdate.Side.BUY, buf, 0);
        assertEquals(0, m.size());
    }

    // ─── DeepMessageRouter dispatch ──────────────────────────────────────────

    @Test
    void routerDispatchesBuyAndSellPlu() {
        byte[] buyBuf = concat(bytes(0x38, 0x01), TS_BYTES, ZIEXT, SIZE_9700, PRICE_99_05);
        IexMessage buy = DeepMessageRouter.decode((byte) 0x38, buyBuf, 0);
        assertInstanceOf(PriceLevelUpdate.class, buy);
        assertEquals(PriceLevelUpdate.Side.BUY, ((PriceLevelUpdate) buy).side());

        byte[] sellBuf = concat(bytes(0x35, 0x01), TS_BYTES, ZIEXT, SIZE_9700, PRICE_99_05);
        IexMessage sell = DeepMessageRouter.decode((byte) 0x35, sellBuf, 0);
        assertInstanceOf(PriceLevelUpdate.class, sell);
        assertEquals(PriceLevelUpdate.Side.SELL, ((PriceLevelUpdate) sell).side());
    }

    @Test
    void routerReusesTopsDecoderForTradeReport() {
        // TradeReport body shape: Type(1) + SaleFlags(1) + TS(8) + Sym(8) + Size(4)
        //                       + Price(8) + TradeID(8) = 38B
        byte[] buf = concat(
                bytes(0x54, 0x00),
                TS_BYTES,
                ZIEXT,
                bytes(0x64, 0x00, 0x00, 0x00),                    // Size = 100
                PRICE_99_05,
                bytes(0x96, 0x8f, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00));  // TradeID = 167830

        IexMessage m = DeepMessageRouter.decode((byte) 0x54, buf, 0);
        assertInstanceOf(TradeReport.class, m);
        TradeReport tr = (TradeReport) m;
        assertEquals("ZIEXT", tr.symbol());
        assertEquals(100, tr.size());
    }

    @Test
    void routerReusesTopsDecoderForTradeBreak() {
        byte[] buf = concat(
                bytes(0x42, 0x00),
                TS_BYTES, ZIEXT,
                bytes(0x64, 0x00, 0x00, 0x00),
                PRICE_99_05,
                bytes(0x96, 0x8f, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00));

        IexMessage m = DeepMessageRouter.decode((byte) 0x42, buf, 0);
        assertInstanceOf(TradeBreak.class, m);
    }

    @Test
    void routerReusesTopsDecoderForOfficialPrice() {
        // OfficialPrice: Type(1) + PriceType(1) + TS(8) + Sym(8) + Price(8) = 26B
        byte[] buf = concat(
                bytes(0x58, 0x51),                   // Type = X, PriceType = Q (opening)
                TS_BYTES, ZIEXT,
                PRICE_99_05);

        IexMessage m = DeepMessageRouter.decode((byte) 0x58, buf, 0);
        assertInstanceOf(OfficialPrice.class, m);
    }

    @Test
    void routerRejectsUnknownTypeByte() {
        byte[] buf = new byte[64];
        assertThrows(IllegalArgumentException.class,
                () -> DeepMessageRouter.decode((byte) 0x7E, buf, 0));   // '~' is not a DEEP type
    }

    @Test
    void routerDispatchesAdminMessagesByCommonHandler() {
        // SystemEvent ('S', 0x53) body: Type(1) + Event(1) + TS(8) = 10B
        byte[] buf = concat(
                bytes(0x53, 0x4F),       // Type = S, Event = O (Start of Messages)
                TS_BYTES);

        IexMessage m = DeepMessageRouter.decode((byte) 0x53, buf, 0);
        assertEquals("SystemEvent", m.getClass().getSimpleName());
    }

    @Test
    void isDeepTradingTypeRecognizesPluAndShared() {
        assertTrue(DeepMessageRouter.isDeepTradingType((byte) 0x38));
        assertTrue(DeepMessageRouter.isDeepTradingType((byte) 0x35));
        assertTrue(DeepMessageRouter.isDeepTradingType(TradeReport.MESSAGE_TYPE));
        assertTrue(DeepMessageRouter.isDeepTradingType(TradeBreak.MESSAGE_TYPE));
        assertTrue(DeepMessageRouter.isDeepTradingType(OfficialPrice.MESSAGE_TYPE));
        assertTrue(DeepMessageRouter.isDeepTradingType(AuctionInformation.MESSAGE_TYPE));
        assertFalse(DeepMessageRouter.isDeepTradingType((byte) 0x53));    // admin S
        assertFalse(DeepMessageRouter.isDeepTradingType((byte) 0x61));    // DPLS AddOrder 'a'
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static byte[] bytes(final int... vals) {
        byte[] out = new byte[vals.length];
        for (int i = 0; i < vals.length; i++) out[i] = (byte) vals[i];
        return out;
    }

    private static byte[] concat(final byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] out = new byte[total];
        int o = 0;
        for (byte[] a : arrays) { System.arraycopy(a, 0, out, o, a.length); o += a.length; }
        return out;
    }
}
