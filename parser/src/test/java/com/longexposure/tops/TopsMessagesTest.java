package com.longexposure.tops;

import com.longexposure.admin.SystemEvent;
import com.longexposure.admin.TradingStatus;
import com.longexposure.wire.IexMessage;
import com.longexposure.wire.SaleConditionFlags;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-level decoder tests for the 5 TOPS trading messages plus the
 * TopsMessageRouter. Each test case transcribes a worked example from
 * the TOPS 1.66 spec PDF (pages cited inline).
 *
 * <p>Note on timestamp annotations: TOPS spec example bytes for trading
 * messages (Q/T/X/B) are annotated in Eastern Time, not UTC. On-wire
 * values are always UTC per the spec text. See AdminMessagesTest for the
 * fuller writeup. Auction examples on later pages appear to use UTC.
 */
class TopsMessagesTest {

    private static final byte[] ZIEXT =
            { 0x5a, 0x49, 0x45, 0x58, 0x54, 0x20, 0x20, 0x20 };

    // 2016-08-23 19:30:32.572715948 UTC (spec annotates as 15:30:32 ET)
    private static final byte[] TS_Q = bytes(0xac, 0x63, 0xc0, 0x20, 0x96, 0x86, 0x6d, 0x14);
    private static final long TS_Q_UTC = 1471980632_572715948L;

    // 2016-08-23 19:31:23.662974915 UTC
    private static final byte[] TS_T = bytes(0xc3, 0xdf, 0xf7, 0x05, 0xa2, 0x86, 0x6d, 0x14);
    private static final long TS_T_UTC = 1471980683_662974915L;

    // 2016-08-23 19:32:04.912754610 UTC
    private static final byte[] TS_B = bytes(0xb2, 0x8f, 0xa5, 0xa0, 0xab, 0x86, 0x6d, 0x14);
    private static final long TS_B_UTC = 1471980724_912754610L;

    // 2017-04-17 09:30:00.000000000 UTC (spec annotation here is correct UTC)
    private static final byte[] TS_X = bytes(0x00, 0xf0, 0x30, 0x2a, 0x5b, 0x25, 0xb6, 0x14);
    private static final long TS_X_UTC = 1492421400_000000000L;

    // 2017-04-17 15:50:12.462929885 UTC (Auction Information example — appears UTC)
    private static final byte[] TS_A = bytes(0xdd, 0xc7, 0xf0, 0x9a, 0x1a, 0x3a, 0xb6, 0x14);
    private static final long TS_A_UTC = 1492444212_462929885L;

    // $99.05 in IEX 4-decimal-implied price encoding (990500 raw)
    private static final byte[] PRICE_99_05 = bytes(0x24, 0x1d, 0x0f, 0x00, 0x00, 0x00, 0x00, 0x00);

    // ─── Quote Update (TOPS 1.66 spec, page 17) ───────────────────────────────

    @Test
    void decodesQuoteUpdate() {
        byte[] buf = concat(
                bytes(0x51,                               // Type = Q
                      0x00),                              // Flags = 0 (active, regular session)
                TS_Q,
                ZIEXT,
                bytes(0xe4, 0x25, 0x00, 0x00),            // Bid Size = 9700
                PRICE_99_05,                              // Bid Price = $99.05
                bytes(0xec, 0x1d, 0x0f, 0x00,             // Ask Price = $99.07 (990700 raw)
                      0x00, 0x00, 0x00, 0x00),
                bytes(0xe8, 0x03, 0x00, 0x00));           // Ask Size = 1000

        QuoteUpdate m = QuoteUpdate.decode(buf, 0);

        assertEquals("ZIEXT", m.symbol());
        assertEquals(9700, m.bidSize());
        assertEquals(990500L, m.bidPriceRaw());
        assertEquals(99.05, m.bidPrice(), 1e-9);
        assertEquals(990700L, m.askPriceRaw());
        assertEquals(99.07, m.askPrice(), 1e-9);
        assertEquals(1000, m.askSize());
        assertFalse(m.isSymbolHalted());
        assertFalse(m.isOffHoursSession());
        assertEquals(TS_Q_UTC, m.timestampNanos());
        assertEquals(QuoteUpdate.MESSAGE_TYPE, m.messageType());
    }

    @Test
    void quoteUpdateFlagsHaltedAndOffHours() {
        byte[] buf = concat(
                bytes(0x51, 0xc0),                        // Flags = 0xC0 (halted + off-hours)
                TS_Q,
                ZIEXT,
                bytes(0, 0, 0, 0),
                new byte[8], new byte[8],
                bytes(0, 0, 0, 0));

        QuoteUpdate m = QuoteUpdate.decode(buf, 0);
        assertTrue(m.isSymbolHalted());
        assertTrue(m.isOffHoursSession());
    }

    // ─── Trade Report (TOPS 1.66 spec, page 19) ───────────────────────────────

    @Test
    void decodesTradeReport() {
        byte[] buf = concat(
                bytes(0x54,                               // Type = T
                      0x00),                              // Sale Condition Flags = 0
                TS_T,
                ZIEXT,
                bytes(0x64, 0x00, 0x00, 0x00),            // Size = 100
                PRICE_99_05,                              // Price = $99.05
                bytes(0x96, 0x8f, 0x06, 0x00,             // Trade ID = 429974
                      0x00, 0x00, 0x00, 0x00));

        TradeReport m = TradeReport.decode(buf, 0);

        assertEquals("ZIEXT", m.symbol());
        assertEquals(100, m.size());
        assertEquals(990500L, m.priceRaw());
        assertEquals(99.05, m.price(), 1e-9);
        assertEquals(429974L, m.tradeId());
        assertEquals(TS_T_UTC, m.timestampNanos());
        assertFalse(m.conditions().isIntermarketSweep());
        assertFalse(m.conditions().isExtendedHours());
        assertFalse(m.conditions().isOddLot());
        assertTrue(m.conditions().isLastSaleEligible());
        assertTrue(m.conditions().isHighLowEligible());
    }

    @Test
    void tradeReportFlagsAllSet() {
        byte[] buf = concat(
                bytes(0x54, 0xf8),                        // ISO + Ext.Hours + OddLot + TTE + SPC
                TS_T,
                ZIEXT,
                bytes(1, 0, 0, 0),
                PRICE_99_05,
                new byte[8]);

        TradeReport m = TradeReport.decode(buf, 0);
        SaleConditionFlags c = m.conditions();
        assertTrue(c.isIntermarketSweep());
        assertTrue(c.isExtendedHours());
        assertTrue(c.isOddLot());
        assertTrue(c.isTradeThroughExempt());
        assertTrue(c.isSinglePriceCross());
        assertFalse(c.isLastSaleEligible());  // odd lot disqualifies
        assertFalse(c.isHighLowEligible());   // odd lot + extended + cross all disqualify
        assertTrue(c.isVolumeEligible());     // always volume-eligible
    }

    // ─── Official Price (TOPS 1.66 spec, page 20) ─────────────────────────────

    @Test
    void decodesOfficialPrice() {
        byte[] buf = concat(
                bytes(0x58, 0x51),                        // Type = X, PriceType = Q (Opening)
                TS_X,
                ZIEXT,
                PRICE_99_05);                             // Official Price = $99.05

        OfficialPrice m = OfficialPrice.decode(buf, 0);

        assertEquals(OfficialPrice.PriceType.OPENING, m.priceType());
        assertEquals("ZIEXT", m.symbol());
        assertEquals(990500L, m.priceRaw());
        assertEquals(99.05, m.price(), 1e-9);
        assertEquals(TS_X_UTC, m.timestampNanos());
    }

    @Test
    void decodesOfficialClosingPrice() {
        byte[] buf = concat(
                bytes(0x58, 0x4d),                        // PriceType = M (Closing)
                TS_X, ZIEXT, PRICE_99_05);
        assertEquals(OfficialPrice.PriceType.CLOSING, OfficialPrice.decode(buf, 0).priceType());
    }

    // ─── Trade Break (TOPS 1.66 spec, page 22) ────────────────────────────────

    @Test
    void decodesTradeBreak() {
        byte[] buf = concat(
                bytes(0x42, 0x00),                        // Type = B, no flags
                TS_B,
                ZIEXT,
                bytes(0x64, 0x00, 0x00, 0x00),            // Size = 100
                PRICE_99_05,                              // Price = $99.05
                bytes(0x96, 0x8f, 0x06, 0x00,             // Trade ID = 429974 (the original trade)
                      0x00, 0x00, 0x00, 0x00));

        TradeBreak m = TradeBreak.decode(buf, 0);

        assertEquals("ZIEXT", m.symbol());
        assertEquals(100, m.size());
        assertEquals(990500L, m.priceRaw());
        assertEquals(429974L, m.brokenTradeId());
        assertEquals(TS_B_UTC, m.timestampNanos());
    }

    // ─── Auction Information (TOPS 1.66 spec, page 23-24) ─────────────────────

    @Test
    void decodesAuctionInformation() {
        byte[] buf = concat(
                bytes(0x41, 0x43),                        // Type = A, AuctionType = C (Closing)
                TS_A,
                ZIEXT,
                bytes(0xa0, 0x86, 0x01, 0x00),            // Paired Shares = 100,000
                PRICE_99_05,                              // Reference Price = $99.05
                bytes(0x18, 0x1f, 0x0f, 0x00,             // Indicative Clearing = $99.10
                      0x00, 0x00, 0x00, 0x00),
                bytes(0x10, 0x27, 0x00, 0x00),            // Imbalance Shares = 10,000
                bytes(0x42),                              // Imbalance Side = B (Buy)
                bytes(0x00),                              // Extension Number = 0
                bytes(0x80, 0xe6, 0xf4, 0x58),            // Scheduled Auction Time = 1,492,444,800
                                                          // = 2017-04-17 16:00:00 UTC
                bytes(0x0c, 0x21, 0x0f, 0x00,             // Auction Book Clearing = $99.15
                      0x00, 0x00, 0x00, 0x00),
                bytes(0xc0, 0x1c, 0x0f, 0x00,             // Collar Reference Price = $99.04
                      0x00, 0x00, 0x00, 0x00),
                bytes(0xa4, 0x99, 0x0d, 0x00,             // Lower Auction Collar = $89.13
                      0x00, 0x00, 0x00, 0x00),
                bytes(0xdc, 0x9f, 0x10, 0x00,             // Upper Auction Collar = $108.95
                      0x00, 0x00, 0x00, 0x00));

        AuctionInformation m = AuctionInformation.decode(buf, 0);

        assertEquals(AuctionInformation.AuctionType.CLOSING, m.auctionType());
        assertEquals("ZIEXT", m.symbol());
        assertEquals(100_000, m.pairedShares());
        assertEquals(990500L,  m.referencePriceRaw());
        assertEquals(991000L,  m.indicativeClearingPriceRaw());
        assertEquals(10_000, m.imbalanceShares());
        assertEquals(AuctionInformation.ImbalanceSide.BUY, m.imbalanceSide());
        assertEquals(0, m.extensionNumber());
        assertEquals(1492444800L, m.scheduledAuctionTimeEpochSeconds()); // 2017-04-17 16:00:00 UTC
        assertEquals(991500L,  m.auctionBookClearingPriceRaw());
        assertEquals(990400L,  m.collarReferencePriceRaw());
        assertEquals(891300L,  m.lowerAuctionCollarRaw());
        assertEquals(1089500L, m.upperAuctionCollarRaw());
        assertEquals(99.15, m.auctionBookClearingPrice(), 1e-9);
        assertEquals(TS_A_UTC, m.timestampNanos());
    }

    // ─── Router ───────────────────────────────────────────────────────────────

    @Test
    void routerDispatchesAdminTypeToAdminDecoder() {
        // 'S' SystemEvent
        byte[] buf = bytes(0x53, 0x45, 0x00, 0xa0, 0x99, 0x97, 0xe9, 0x3d, 0xb6, 0x14);
        IexMessage m = TopsMessageRouter.decode((byte) 0x53, buf, 0);
        assertInstanceOf(SystemEvent.class, m);
    }

    @Test
    void routerDispatchesTopsTradingType() {
        byte[] q = concat(bytes(0x51, 0x00), TS_Q, ZIEXT,
                bytes(0, 0, 0, 0), new byte[8], new byte[8], bytes(0, 0, 0, 0));
        IexMessage m = TopsMessageRouter.decode((byte) 0x51, q, 0);
        assertInstanceOf(QuoteUpdate.class, m);
    }

    @Test
    void routerHandlesAllFiveTopsTradingTypes() {
        assertTrue(TopsMessageRouter.isTopsTradingType(QuoteUpdate.MESSAGE_TYPE));
        assertTrue(TopsMessageRouter.isTopsTradingType(TradeReport.MESSAGE_TYPE));
        assertTrue(TopsMessageRouter.isTopsTradingType(TradeBreak.MESSAGE_TYPE));
        assertTrue(TopsMessageRouter.isTopsTradingType(OfficialPrice.MESSAGE_TYPE));
        assertTrue(TopsMessageRouter.isTopsTradingType(AuctionInformation.MESSAGE_TYPE));
        assertFalse(TopsMessageRouter.isTopsTradingType(SystemEvent.MESSAGE_TYPE));
        assertFalse(TopsMessageRouter.isTopsTradingType(TradingStatus.MESSAGE_TYPE));
    }

    @Test
    void routerThrowsOnUnknownType() {
        assertThrows(IllegalArgumentException.class,
                () -> TopsMessageRouter.decode((byte) 0x7f, new byte[100], 0));
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
