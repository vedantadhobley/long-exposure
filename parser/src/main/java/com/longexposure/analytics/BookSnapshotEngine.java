package com.longexposure.analytics;

import com.longexposure.dpls.DplsMessageRouter;
import com.longexposure.dpls.OrderBook;
import com.longexposure.dpls.OrderBookManager;
import com.longexposure.pcap.PcapReader;
import com.longexposure.transport.IexTpDecoder;
import com.longexposure.wire.Bytes;
import com.longexposure.wire.IexMessage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.Consumer;

/**
 * Book-state snapshots for the book-replay analytics tier. One decode-only
 * pass over a day's DPLS pcap, feeding the validated {@link OrderBookManager}
 * (the same reconstruction proven 99.4% vs the real TOPS feed in
 * {@code DplsBboCrossValidator}), capturing each requested symbol's book the
 * moment the IEX-TP send-time clock crosses the request time.
 *
 * <p>The book "at time T" is the state after every message with send-time ≤ T;
 * we snapshot just after applying the packet whose send-time first reaches T,
 * so a snapshot is accurate to within one packet (~microseconds) of T — far
 * finer than the event granularity the stats need.
 *
 * <p>The engine is intentionally dumb about WHAT the snapshots mean: it returns
 * raw book state (touch + total displayed size per side) tagged with the
 * caller's {@code selectedId} + {@code role}; {@code EnrichAnalyticsActivity}
 * turns those into effective-spread / depth-from-touch / %-of-book / spread.
 *
 * <p>Cost ≈ a parse-only pass (~2–3 min/day; decode alone is ~97 s). Holds all
 * symbols' books in memory like the validator does — bounded by resting orders,
 * not the 360M-message daily flow.
 */
public final class BookSnapshotEngine {

    private BookSnapshotEngine() {}

    /** Snapshot {@code symbol}'s book at {@code atNanos}, tagged for the caller. */
    public record Request(long selectedId, String symbol, long atNanos, String role) {}

    /**
     * Book state captured for a {@link Request}. {@code captured=false} means the
     * stream ended before the request's time (or the symbol never traded) — the
     * caller omits the dependent stat rather than narrating a guess.
     */
    public record Snapshot(long selectedId, String role,
                           OptionalLong bestBidPriceRaw, OptionalLong bestAskPriceRaw,
                           long totalBidSize, long totalAskSize, boolean captured) {

        static Snapshot missing(final Request r) {
            return new Snapshot(r.selectedId(), r.role(), OptionalLong.empty(), OptionalLong.empty(),
                    0L, 0L, false);
        }
    }

    private static final long HEARTBEAT_EVERY = 5_000_000L;

    /**
     * @param pcapPath  the day's DPLS {@code .pcap.gz}
     * @param requests  snapshot points (any order; processed in time order)
     * @param heartbeat called periodically with progress (keep a Temporal activity alive)
     * @return one {@link Snapshot} per request, in the SAME order as {@code requests}
     */
    public static List<Snapshot> run(final Path pcapPath,
                                     final List<Request> requests,
                                     final Consumer<String> heartbeat) throws IOException {
        // Index requests so we can fire them in time order yet return them in input order.
        Integer[] order = new Integer[requests.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) ->
                Long.compare(requests.get(a).atNanos(), requests.get(b).atNanos()));
        Snapshot[] out = new Snapshot[requests.size()];

        OrderBookManager books = new OrderBookManager();
        int fired = 0;                       // how many (in time order) we've already snapshotted
        long messageCount = 0, nextHeartbeat = HEARTBEAT_EVERY;

        try (PcapReader reader = PcapReader.open(pcapPath)) {
            byte[] payload;
            while ((payload = reader.nextUdpPayload()) != null) {
                if (payload.length < IexTpDecoder.HEADER_BYTES) continue;
                IexTpDecoder.Header h = IexTpDecoder.decode(payload);
                if (h.messageCount() == 0 || h.payloadLength() == 0) continue;  // heartbeat packet

                int pos = IexTpDecoder.HEADER_BYTES;
                int endOfBlock = IexTpDecoder.HEADER_BYTES + h.payloadLength();
                for (int i = 0; i < h.messageCount() && pos + 2 <= endOfBlock; i++) {
                    int msgLen = Bytes.readShortLE(payload, pos);
                    pos += 2;
                    if (msgLen == 0 || pos + msgLen > endOfBlock) break;
                    byte typeByte = payload[pos];
                    messageCount++;
                    try {
                        IexMessage m = DplsMessageRouter.decode(typeByte, payload, pos);
                        books.apply(m);
                    } catch (IllegalArgumentException ignored) {
                        // unknown/malformed message type — skip (matches the parser's tolerance)
                    }
                    pos += msgLen;
                }

                // Clock has advanced to this packet's send-time; fire any pending
                // requests whose time it has now reached. Book reflects ≤ this packet.
                long clock = h.sendTimeNanos();
                while (fired < order.length && requests.get(order[fired]).atNanos() <= clock) {
                    Request r = requests.get(order[fired]);
                    out[order[fired]] = snapshot(books, r);
                    fired++;
                }

                if (messageCount >= nextHeartbeat) {
                    heartbeat.accept("book_replay:" + messageCount + " fired:" + fired);
                    nextHeartbeat += HEARTBEAT_EVERY;
                }
            }
        }

        // Any requests past end-of-stream never fired.
        for (int i = 0; i < order.length; i++) {
            if (out[order[i]] == null) out[order[i]] = Snapshot.missing(requests.get(order[i]));
        }
        return new ArrayList<>(java.util.Arrays.asList(out));
    }

    private static Snapshot snapshot(final OrderBookManager books, final Request r) {
        OrderBook book = books.book(r.symbol());
        if (book == null) return Snapshot.missing(r);
        return new Snapshot(r.selectedId(), r.role(),
                book.bestBidPriceRaw(), book.bestAskPriceRaw(),
                book.totalBidSize(), book.totalAskSize(), true);
    }
}
