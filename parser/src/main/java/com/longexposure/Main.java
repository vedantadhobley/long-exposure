package com.longexposure;

import com.longexposure.admin.OperationalHaltStatus;
import com.longexposure.admin.RetailLiquidityIndicator;
import com.longexposure.admin.SecurityDirectory;
import com.longexposure.admin.SecurityEvent;
import com.longexposure.admin.ShortSalePriceTestStatus;
import com.longexposure.admin.SystemEvent;
import com.longexposure.admin.TradingStatus;
import com.longexposure.pcap.PcapReader;
import com.longexposure.tops.AuctionInformation;
import com.longexposure.tops.OfficialPrice;
import com.longexposure.tops.QuoteUpdate;
import com.longexposure.tops.TopsMessageRouter;
import com.longexposure.tops.TradeBreak;
import com.longexposure.tops.TradeReport;
import com.longexposure.transport.IexTpDecoder;
import com.longexposure.wire.Bytes;
import com.longexposure.wire.IexMessage;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Entry point for the parser/worker process.
 *
 * Day 1–7 smoke test: read a real HIST .pcap.gz, walk the IEX-TP message
 * blocks past the pre-market heartbeats, decode each TOPS message via
 * TopsMessageRouter, print samples + a per-type histogram. This is the
 * end-to-end "real market data going through the whole stack" milestone.
 *
 * Stays the entry point — once Temporal SDK plumbing lands it will
 * dispatch to either the smoke test (when IEX_PCAP_FILE is set) or the
 * Temporal worker registration.
 */
public final class Main {

    private static final int DEFAULT_PRINT_LIMIT = 20;
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSSSSS").withZone(ZoneOffset.UTC);

    private Main() {}

    public static void main(final String[] args) throws Exception {
        String filePath = firstNonNull(
                args.length > 0 ? args[0] : null,
                System.getenv("IEX_PCAP_FILE"));

        if (filePath == null) {
            System.out.println("long-exposure-parser starting (idle stub)");
            System.out.println("Set IEX_PCAP_FILE or pass a path as args[0] to run the smoke test.");
            System.out.println("TEMPORAL_HOST=" + System.getenv("TEMPORAL_HOST"));
            System.out.println("POSTGRES_HOST=" + System.getenv("POSTGRES_HOST"));
            System.out.println("LLAMA_URL=" + System.getenv("LLAMA_URL"));
            Thread.currentThread().join();
            return;
        }

        int printLimit = parseIntOrDefault(System.getenv("IEX_PRINT_LIMIT"), DEFAULT_PRINT_LIMIT);
        smokeTest(Path.of(filePath), printLimit);
    }

    private static void smokeTest(final Path path, final int printLimit) throws Exception {
        System.out.println("== TOPS smoke test ==");
        System.out.println("file:        " + path);
        System.out.println("print limit: " + printLimit + " messages (after first non-heartbeat)");

        long packetCount = 0;
        long heartbeatCount = 0;
        long messageCount = 0;
        int printed = 0;
        Map<Byte, Long> typeHistogram = new HashMap<>();

        try (PcapReader reader = PcapReader.open(path)) {
            System.out.println("pcap:        format=" + reader.format()
                    + " byteOrder=" + reader.byteOrder()
                    + " nanosecondTs=" + reader.isNanosecondTimestamps());
            System.out.println();

            byte[] payload;
            while ((payload = reader.nextUdpPayload()) != null) {
                packetCount++;
                if (payload.length < IexTpDecoder.HEADER_BYTES) continue;
                IexTpDecoder.Header h = IexTpDecoder.decode(payload);
                if (h.messageCount() == 0 || h.payloadLength() == 0) {
                    heartbeatCount++;
                    continue;
                }

                int pos = IexTpDecoder.HEADER_BYTES;
                int endOfBlock = IexTpDecoder.HEADER_BYTES + h.payloadLength();
                for (int i = 0; i < h.messageCount() && pos + 2 <= endOfBlock; i++) {
                    int msgLen = Bytes.readShortLE(payload, pos);
                    pos += 2;
                    if (msgLen == 0 || pos + msgLen > endOfBlock) break;
                    byte typeByte = payload[pos];
                    messageCount++;
                    typeHistogram.merge(typeByte, 1L, Long::sum);

                    if (printed < printLimit) {
                        try {
                            IexMessage m = TopsMessageRouter.decode(typeByte, payload, pos);
                            System.out.printf("[pkt %d msg %d] %s%n",
                                    packetCount, messageCount, format(m));
                            printed++;
                        } catch (IllegalArgumentException e) {
                            System.err.printf("[pkt %d msg %d] decode failed type=0x%02x: %s%n",
                                    packetCount, messageCount, typeByte & 0xff, e.getMessage());
                            printed++;
                        }
                    }
                    pos += msgLen;
                }
            }
        }

        System.out.println();
        System.out.println("== summary ==");
        System.out.println("packets:    " + packetCount);
        System.out.println("heartbeats: " + heartbeatCount);
        System.out.println("messages:   " + messageCount);
        System.out.println("by type:");
        typeHistogram.entrySet().stream()
                .sorted(Map.Entry.<Byte, Long>comparingByValue().reversed())
                .forEach(e -> System.out.printf("  0x%02x (%c) %s%n",
                        e.getKey() & 0xff,
                        printable(e.getKey()),
                        e.getValue()));
    }

    private static String format(final IexMessage m) {
        return switch (m) {
            case SystemEvent e         -> String.format("SystemEvent           %s  @%s",
                    e.event(), formatNanos(e.timestampNanos()));
            case SecurityDirectory d   -> String.format("SecurityDirectory     %-8s round=%d POC=$%.4f tier=%s  @%s",
                    d.symbol(), d.roundLotSize(), d.adjustedPocPrice(), d.luldTier(),
                    formatNanos(d.timestampNanos()));
            case TradingStatus s       -> String.format("TradingStatus         %-8s %s reason=%s  @%s",
                    s.symbol(), s.status(), s.reason(), formatNanos(s.timestampNanos()));
            case RetailLiquidityIndicator i -> String.format("RetailLiquidityInd    %-8s %s  @%s",
                    i.symbol(), i.indicator(), formatNanos(i.timestampNanos()));
            case OperationalHaltStatus o -> String.format("OperationalHalt       %-8s %s  @%s",
                    o.symbol(), o.status(), formatNanos(o.timestampNanos()));
            case ShortSalePriceTestStatus p -> String.format("ShortSalePriceTest    %-8s %s detail=%s  @%s",
                    p.symbol(), p.status(), p.detail(), formatNanos(p.timestampNanos()));
            case SecurityEvent se      -> String.format("SecurityEvent         %-8s %s  @%s",
                    se.symbol(), se.event(), formatNanos(se.timestampNanos()));
            case QuoteUpdate q         -> String.format("QuoteUpdate           %-8s %dx$%.4f / $%.4fx%d  @%s",
                    q.symbol(), q.bidSize(), q.bidPrice(), q.askPrice(), q.askSize(),
                    formatNanos(q.timestampNanos()));
            case TradeReport t         -> String.format("TradeReport           %-8s %d @ $%.4f  id=%d  @%s",
                    t.symbol(), t.size(), t.price(), t.tradeId(), formatNanos(t.timestampNanos()));
            case TradeBreak b          -> String.format("TradeBreak            %-8s broken id=%d  @%s",
                    b.symbol(), b.brokenTradeId(), formatNanos(b.timestampNanos()));
            case OfficialPrice op      -> String.format("OfficialPrice         %-8s %s = $%.4f  @%s",
                    op.symbol(), op.priceType(), op.price(), formatNanos(op.timestampNanos()));
            case AuctionInformation a  -> String.format("AuctionInformation    %-8s %s paired=%d ref=$%.4f ind=$%.4f  @%s",
                    a.symbol(), a.auctionType(), a.pairedShares(),
                    a.referencePrice(), a.indicativeClearingPrice(),
                    formatNanos(a.timestampNanos()));
            default -> String.format("(other) type=0x%02x ts=%s",
                    m.messageType() & 0xff, formatNanos(m.timestampNanos()));
        };
    }

    private static char printable(final byte b) {
        int v = b & 0xff;
        return v >= 0x20 && v < 0x7f ? (char) v : '?';
    }

    private static String formatNanos(final long nanosSinceEpoch) {
        long seconds = nanosSinceEpoch / 1_000_000_000L;
        long nanos = nanosSinceEpoch % 1_000_000_000L;
        if (nanos < 0) {
            nanos += 1_000_000_000L;
            seconds -= 1;
        }
        return TS_FMT.format(Instant.ofEpochSecond(seconds, nanos));
    }

    private static String firstNonNull(final String a, final String b) {
        return a != null ? a : b;
    }

    private static int parseIntOrDefault(final String s, final int dflt) {
        if (s == null || s.isBlank()) return dflt;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
