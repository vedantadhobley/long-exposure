package com.longexposure;

import com.longexposure.admin.OperationalHaltStatus;
import com.longexposure.admin.RetailLiquidityIndicator;
import com.longexposure.admin.SecurityDirectory;
import com.longexposure.admin.SecurityEvent;
import com.longexposure.admin.ShortSalePriceTestStatus;
import com.longexposure.admin.SystemEvent;
import com.longexposure.admin.TradingStatus;
import com.longexposure.deepplus.AddOrder;
import com.longexposure.deepplus.ClearBook;
import com.longexposure.deepplus.DeepPlusMessageRouter;
import com.longexposure.deepplus.OrderDelete;
import com.longexposure.deepplus.OrderExecuted;
import com.longexposure.deepplus.OrderModify;
import com.longexposure.pcap.PcapReader;
import com.longexposure.storage.SchemaManager;
import com.longexposure.storage.TimescaleWriter;
import com.longexposure.tops.AuctionInformation;
import com.longexposure.tops.OfficialPrice;
import com.longexposure.tops.QuoteUpdate;
import com.longexposure.tops.TopsMessageRouter;
import com.longexposure.tops.TradeReport;
import com.longexposure.transport.IexTpDecoder;
import com.longexposure.wire.Bytes;
import com.longexposure.wire.IexMessage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Entry point for the parser/worker process.
 *
 * <p>Smoke test: read a real HIST {@code .pcap.gz} (TOPS or DEEP+ — feed is
 * auto-detected from the IEX-TP Protocol ID of the first non-heartbeat
 * packet), walk message blocks past heartbeats, decode every message via
 * the feed-appropriate router.
 *
 * <p>Two cross-validation paths run after parsing:
 * <ol>
 *   <li>Per-message-type histogram (sanity-check that distribution matches
 *       what we expect for that feed)
 *   <li>Per-symbol trade-volume aggregate. For TOPS that's
 *       {@code SUM(TradeReport.size)}; for DEEP+ it's
 *       {@code SUM(OrderExecuted.size + Trade.size)}. When running DEEP+
 *       and a TOPS dataset for the same day is already loaded in Postgres,
 *       we diff the two aggregates per-symbol — same matching engine,
 *       totals must match.
 * </ol>
 *
 * <p>Future: when Temporal SDK plumbing lands, this becomes the worker
 * registration when {@code IEX_PCAP_FILE} is unset.
 */
public final class Main {

    private static final int DEFAULT_PRINT_LIMIT = 20;
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSSSSS").withZone(ZoneOffset.UTC);

    private enum Feed {
        TOPS("TOPS"), DEEPPLUS("DEEP+");
        final String label;
        Feed(final String label) { this.label = label; }
    }

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
        long maxPackets = parseLongOrDefault(System.getenv("IEX_MAX_PACKETS"), Long.MAX_VALUE);
        boolean writeDb = "true".equalsIgnoreCase(System.getenv("IEX_WRITE_DB"));
        boolean crossValidate = "true".equalsIgnoreCase(System.getenv("IEX_CROSS_VALIDATE"));
        smokeTest(Path.of(filePath), printLimit, maxPackets, writeDb, crossValidate);
    }

    private static void smokeTest(final Path path, final int printLimit, final long maxPackets,
                                  final boolean writeDb, final boolean crossValidate) throws Exception {
        System.out.println("== IEX smoke test ==");
        System.out.println("file:             " + path);
        System.out.println("print limit:      " + printLimit + " messages (after first non-heartbeat)");
        System.out.println("max packets:      " + (maxPackets == Long.MAX_VALUE ? "unlimited" : String.valueOf(maxPackets)));
        System.out.println("write to DB:      " + writeDb);
        System.out.println("cross-validate:   " + crossValidate);

        long packetCount = 0;
        long heartbeatCount = 0;
        long messageCount = 0;
        int printed = 0;
        Feed feed = null;
        Map<Byte, Long> typeHistogram = new HashMap<>();
        Map<String, Long> tradeVolumeBySymbol = new HashMap<>();

        Connection conn = null;
        TimescaleWriter writer = null;

        long startNanos = System.nanoTime();
        try (PcapReader reader = PcapReader.open(path)) {
            System.out.println("pcap:             format=" + reader.format()
                    + " byteOrder=" + reader.byteOrder()
                    + " nanosecondTs=" + reader.isNanosecondTimestamps());
            System.out.println();

            byte[] payload;
            while ((payload = reader.nextUdpPayload()) != null) {
                packetCount++;
                if (packetCount > maxPackets) break;
                if (payload.length < IexTpDecoder.HEADER_BYTES) continue;
                IexTpDecoder.Header h = IexTpDecoder.decode(payload);
                if (h.messageCount() == 0 || h.payloadLength() == 0) {
                    heartbeatCount++;
                    continue;
                }

                if (feed == null) {
                    feed = feedFromProtocolId(h.protocolId());
                    System.out.println("feed:             " + feed.label
                            + "  (protocol 0x" + String.format("%04x", h.protocolId()) + ")");
                    if (writeDb) {
                        conn = openConnection();
                        SchemaManager.apply(conn);
                        writer = new TimescaleWriter(conn, feed.label);
                    }
                    System.out.println();
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

                    try {
                        IexMessage m = switch (feed) {
                            case TOPS     -> TopsMessageRouter.decode(typeByte, payload, pos);
                            case DEEPPLUS -> DeepPlusMessageRouter.decode(typeByte, payload, pos);
                        };
                        aggregateTradeVolume(m, tradeVolumeBySymbol);
                        if (writer != null) {
                            writer.writeMessage(m);
                        }
                        if (printed < printLimit) {
                            System.out.printf("[pkt %d msg %d] %s%n",
                                    packetCount, messageCount, format(m));
                            printed++;
                        }
                    } catch (IllegalArgumentException e) {
                        if (printed < printLimit) {
                            System.err.printf("[pkt %d msg %d] decode failed type=0x%02x: %s%n",
                                    packetCount, messageCount, typeByte & 0xff, e.getMessage());
                            printed++;
                        }
                    }
                    pos += msgLen;
                }
            }
        }

        if (writer != null) {
            writer.close();
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        System.out.println();
        System.out.println("== summary ==");
        System.out.println("feed:       " + (feed == null ? "(no data)" : feed.label));
        System.out.println("elapsed:    " + elapsedMs + " ms");
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
        long totalTradedVolume = tradeVolumeBySymbol.values().stream().mapToLong(Long::longValue).sum();
        System.out.println("traded volume: " + totalTradedVolume + " shares across "
                + tradeVolumeBySymbol.size() + " symbols");

        if (writer != null) {
            System.out.println("rows written:");
            writer.totalRows().entrySet().stream()
                    .filter(e -> e.getValue() > 0)
                    .sorted(Map.Entry.<TimescaleWriter.Table, Long>comparingByValue().reversed())
                    .forEach(e -> System.out.printf("  %-16s %s%n", e.getKey().tableName, e.getValue()));
        }

        if (crossValidate && feed == Feed.DEEPPLUS) {
            try (Connection xvConn = (conn != null && !conn.isClosed()) ? conn : openConnection()) {
                crossValidateAgainstTops(xvConn, tradeVolumeBySymbol);
            }
        } else if (conn != null) {
            conn.close();
        }
    }

    private static Feed feedFromProtocolId(final int protocolId) {
        return switch (protocolId) {
            case IexTpDecoder.PROTOCOL_TOPS  -> Feed.TOPS;
            case IexTpDecoder.PROTOCOL_DEEPP -> Feed.DEEPPLUS;
            default -> throw new IllegalArgumentException(
                    "Unsupported IEX-TP protocol id 0x" + String.format("%04x", protocolId)
                    + " — only TOPS (0x8003) and DEEP+ (0x8005) are handled");
        };
    }

    /**
     * Trade-volume aggregation. TOPS: sum TradeReport sizes. DEEP+: sum
     * OrderExecuted + Trade sizes (both contribute to total IEX volume per
     * spec). Trade Breaks are NOT subtracted here — they're rare and would
     * need to be reconciled separately if total accuracy mattered.
     */
    private static void aggregateTradeVolume(final IexMessage m, final Map<String, Long> agg) {
        switch (m) {
            case TradeReport tr      -> agg.merge(tr.symbol(), (long) tr.size(), Long::sum);
            case OrderExecuted ox    -> agg.merge(ox.symbol(), (long) ox.size(), Long::sum);
            case com.longexposure.deepplus.Trade dt
                                     -> agg.merge(dt.symbol(), (long) dt.size(), Long::sum);
            default -> { /* other types don't contribute */ }
        }
    }

    /**
     * Per-symbol trade-volume diff: this run's DEEP+ aggregate vs the
     * TOPS volumes already loaded in the {@code trades} table for the
     * same trading date.
     */
    private static void crossValidateAgainstTops(final Connection conn, final Map<String, Long> deepPlusVolume) throws Exception {
        System.out.println();
        System.out.println("== cross-validation: DEEP+ vs TOPS trade volumes ==");

        // Pull TOPS aggregates for every symbol that traded in either feed.
        Map<String, Long> topsVolume = new HashMap<>();
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT symbol, SUM(size)::BIGINT FROM trades WHERE feed_source = 'TOPS' GROUP BY symbol")) {
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    topsVolume.put(rs.getString(1), rs.getLong(2));
                }
            }
        }

        if (topsVolume.isEmpty()) {
            System.out.println("(no TOPS data in DB to compare against — load TOPS for the same date first)");
            return;
        }

        // Union of symbols on both sides
        TreeMap<String, long[]> joined = new TreeMap<>();  // symbol → [deepPlus, tops]
        deepPlusVolume.forEach((s, v) -> joined.computeIfAbsent(s, k -> new long[2])[0] = v);
        topsVolume.forEach((s, v) -> joined.computeIfAbsent(s, k -> new long[2])[1] = v);

        int matched = 0;
        int mismatched = 0;
        int onlyDeepPlus = 0;
        int onlyTops = 0;
        long absDeltaSum = 0;
        TreeMap<Long, String> topMismatches = new TreeMap<>(java.util.Collections.reverseOrder());
        for (Map.Entry<String, long[]> e : joined.entrySet()) {
            long dp = e.getValue()[0];
            long tops = e.getValue()[1];
            if (dp == 0 && tops > 0) { onlyTops++; continue; }
            if (tops == 0 && dp > 0) { onlyDeepPlus++; continue; }
            long delta = Math.abs(dp - tops);
            absDeltaSum += delta;
            if (delta == 0) {
                matched++;
            } else {
                mismatched++;
                topMismatches.put(delta * 1_000_000L + e.getKey().hashCode(),  // dedupe key
                        String.format("%-10s DEEP+=%d TOPS=%d delta=%+d", e.getKey(), dp, tops, dp - tops));
            }
        }

        System.out.printf("symbols in both feeds:   %d%n", matched + mismatched);
        System.out.printf("  exact match:           %d%n", matched);
        System.out.printf("  mismatched:            %d%n", mismatched);
        System.out.printf("symbols only in DEEP+:   %d%n", onlyDeepPlus);
        System.out.printf("symbols only in TOPS:    %d%n", onlyTops);
        System.out.printf("aggregate |delta|:       %d shares%n", absDeltaSum);
        if (!topMismatches.isEmpty()) {
            System.out.println("top 10 mismatches by absolute delta:");
            topMismatches.values().stream().limit(10).forEach(s -> System.out.println("  " + s));
        }
        if (matched == joined.size() - onlyDeepPlus - onlyTops && onlyDeepPlus == 0 && onlyTops == 0) {
            System.out.println("✓ DEEP+ trade aggregates match TOPS exactly across every symbol.");
        }
    }

    private static Connection openConnection() throws Exception {
        String host = System.getenv().getOrDefault("POSTGRES_HOST", "localhost");
        String port = System.getenv().getOrDefault("POSTGRES_PORT", "5432");
        String db   = System.getenv().getOrDefault("POSTGRES_DB", "longexposure");
        String user = System.getenv().getOrDefault("POSTGRES_USER", "leuser");
        String pwd  = System.getenv().getOrDefault("POSTGRES_PASSWORD", "lepass");
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + db;
        System.out.println("db:               " + url + " (user=" + user + ")");
        return DriverManager.getConnection(url, user, pwd);
    }

    private static String format(final IexMessage m) {
        return switch (m) {
            // Admin (shared across feeds)
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

            // TOPS-specific
            case QuoteUpdate q         -> String.format("QuoteUpdate           %-8s %dx$%.4f / $%.4fx%d  @%s",
                    q.symbol(), q.bidSize(), q.bidPrice(), q.askPrice(), q.askSize(),
                    formatNanos(q.timestampNanos()));
            case TradeReport t         -> String.format("TradeReport           %-8s %d @ $%.4f  id=%d  @%s",
                    t.symbol(), t.size(), t.price(), t.tradeId(), formatNanos(t.timestampNanos()));
            case com.longexposure.tops.TradeBreak tb -> String.format("TradeBreak[TOPS]      %-8s broken id=%d  @%s",
                    tb.symbol(), tb.brokenTradeId(), formatNanos(tb.timestampNanos()));
            case OfficialPrice op      -> String.format("OfficialPrice         %-8s %s = $%.4f  @%s",
                    op.symbol(), op.priceType(), op.price(), formatNanos(op.timestampNanos()));
            case AuctionInformation a  -> String.format("AuctionInformation    %-8s %s paired=%d ref=$%.4f ind=$%.4f  @%s",
                    a.symbol(), a.auctionType(), a.pairedShares(),
                    a.referencePrice(), a.indicativeClearingPrice(),
                    formatNanos(a.timestampNanos()));

            // DEEP+-specific
            case AddOrder ao           -> String.format("AddOrder              %-8s %s %d @ $%.4f  id=%d  @%s",
                    ao.symbol(), ao.side(), ao.size(), ao.price(), ao.orderId(),
                    formatNanos(ao.timestampNanos()));
            case OrderModify om        -> String.format("OrderModify           %-8s id=%d -> %d @ $%.4f  prio=%s  @%s",
                    om.symbol(), om.orderId(), om.size(), om.price(),
                    om.maintainPriority() ? "kept" : "reset", formatNanos(om.timestampNanos()));
            case OrderDelete od        -> String.format("OrderDelete           %-8s id=%d  @%s",
                    od.symbol(), od.orderId(), formatNanos(od.timestampNanos()));
            case OrderExecuted ox      -> String.format("OrderExecuted         %-8s id=%d %d @ $%.4f  trade=%d  @%s",
                    ox.symbol(), ox.orderId(), ox.size(), ox.price(), ox.tradeId(),
                    formatNanos(ox.timestampNanos()));
            case com.longexposure.deepplus.Trade dt -> String.format("Trade[DEEP+]          %-8s %d @ $%.4f  trade=%d  @%s",
                    dt.symbol(), dt.size(), dt.price(), dt.tradeId(), formatNanos(dt.timestampNanos()));
            case com.longexposure.deepplus.TradeBreak dtb -> String.format("TradeBreak[DEEP+]     %-8s broken trade=%d  @%s",
                    dtb.symbol(), dtb.brokenTradeId(), formatNanos(dtb.timestampNanos()));
            case ClearBook cb          -> String.format("ClearBook             %-8s  @%s",
                    cb.symbol(), formatNanos(cb.timestampNanos()));

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

    private static long parseLongOrDefault(final String s, final long dflt) {
        if (s == null || s.isBlank()) return dflt;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
