package com.longexposure;

import com.longexposure.pcap.PcapReader;
import com.longexposure.transport.IexTpDecoder;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Entry point for the parser/worker process.
 *
 * Day 1-3 milestone: read a real HIST .pcap.gz and print the first N
 * IEX-TP headers to stdout. This stays the entry point — once Temporal
 * SDK plumbing lands it will dispatch to either the smoke test (when
 * IEX_PCAP_FILE is set) or the Temporal worker registration.
 */
public final class Main {

    private static final int DEFAULT_HEADERS_TO_PRINT = 10;
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

        int limit = parseIntOrDefault(System.getenv("IEX_HEADER_LIMIT"), DEFAULT_HEADERS_TO_PRINT);
        smokeTest(Path.of(filePath), limit);
    }

    private static void smokeTest(final Path path, final int limit) throws Exception {
        System.out.println("== IEX-TP smoke test ==");
        System.out.println("file:   " + path);
        System.out.println("limit:  " + limit + " packets");

        try (PcapReader reader = PcapReader.open(path)) {
            System.out.println("pcap:   format=" + reader.format()
                    + " linkType=" + reader.linkLayerType()
                    + " byteOrder=" + reader.byteOrder()
                    + " nanosecondTs=" + reader.isNanosecondTimestamps());
            System.out.println();
            System.out.printf("%-4s %-8s %-9s %-11s %-7s %-7s %-22s %-12s %-22s%n",
                    "#", "proto", "channel", "session", "payLen", "msgCnt",
                    "streamOffset", "firstSeq", "sendTime UTC");

            int printed = 0;
            int totalPackets = 0;
            byte[] payload;
            while ((payload = reader.nextUdpPayload()) != null) {
                totalPackets++;
                if (payload.length < IexTpDecoder.HEADER_BYTES) {
                    System.err.println("warn: payload " + totalPackets + " too short ("
                            + payload.length + " bytes)");
                    continue;
                }
                IexTpDecoder.Header h = IexTpDecoder.decode(payload);
                if (printed < limit) {
                    System.out.printf("%-4d %-8s %-9d %-11d %-7d %-7d %-22d %-12d %-22s%n",
                            totalPackets,
                            h.protocolName(),
                            h.channelId(),
                            h.sessionId(),
                            h.payloadLength(),
                            h.messageCount(),
                            h.streamOffset(),
                            h.firstSequenceNumber(),
                            formatNanos(h.sendTimeNanos()));
                    printed++;
                }
                if (printed >= limit) {
                    break;
                }
            }
            System.out.println();
            System.out.println("done: read " + totalPackets + " packet(s), decoded " + printed + " header(s)");
        }
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
