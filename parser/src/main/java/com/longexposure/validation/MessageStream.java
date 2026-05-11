package com.longexposure.validation;

import com.longexposure.deep.DeepMessageRouter;
import com.longexposure.dpls.DplsMessageRouter;
import com.longexposure.pcap.PcapReader;
import com.longexposure.tops.TopsMessageRouter;
import com.longexposure.transport.IexTpDecoder;
import com.longexposure.wire.Bytes;
import com.longexposure.wire.IexMessage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * Peekable, <strong>timestamp-ordered</strong> stream of decoded
 * {@link IexMessage}s from a single .pcap.gz HIST file.
 *
 * <p><b>Why timestamp-ordered, not file-ordered.</b> The IEX-TP file order
 * is by <i>Send Time</i> (when the multicast packet was published).
 * Message {@code Timestamp} fields reflect when the matching engine made
 * the decision — which can be earlier than Send Time, and earlier than
 * messages that appear later in the file. The spec only guarantees
 * per-(type, symbol) timestamp monotonicity, not global. A naive
 * file-order read produces out-of-order timestamps that break any merge
 * algorithm that assumes monotonicity.
 *
 * <p>Concretely observed on 2026-05-08 TOPS: a {@code QuoteUpdate} at
 * timestamp {@code .025137749} appeared in the file <em>after</em>
 * messages with timestamps up to {@code .026229032} — a 1.1 ms gap.
 *
 * <p><b>How.</b> Read messages into a sliding-window
 * {@link PriorityQueue} keyed by timestamp. Only yield a message when
 * its timestamp is older than {@code maxObservedTs - reorderWindowNs} —
 * at that point we're guaranteed no later-read message can have a smaller
 * timestamp. Default window: 1 second, which dominates any plausible
 * publish-vs-event lag.
 *
 * <p>Memory cost is bounded by {@code reorderWindow × peakMsgRate} ≈
 * 1s × ~4 M msg/sec = 4 M buffered messages = ~400 MB. With smaller
 * windows (100 ms ≈ 40 MB) we'd be tighter; 1s is safe headroom.
 */
public final class MessageStream implements AutoCloseable {

    /** Decoder = (typeByte, buf, offset) -> decoded message. Throws on unknown types. */
    @FunctionalInterface
    public interface Decoder {
        IexMessage decode(byte typeByte, byte[] buf, int offset);
    }

    /** Sentinel value returned by {@link #peekTs()} once the stream is drained. */
    public static final long EXHAUSTED = Long.MAX_VALUE;

    /** Default reorder window (1 second). Dominates any plausible publish-vs-event lag. */
    public static final long DEFAULT_REORDER_WINDOW_NS = 1_000_000_000L;

    private final PcapReader reader;
    private final Decoder decoder;
    private final String label;
    private final long reorderWindowNs;

    // Underlying file walker state
    private byte[] payload;
    private IexTpDecoder.Header header;
    private int messageIndex;
    private int posInBlock;
    private int endOfBlock;
    private boolean readerExhausted = false;

    // Reorder buffer. Ties on timestamp broken by file-read sequence so
    // same-ns messages are yielded in their original engine-publication
    // order (e.g., an AddOrder at ts T followed by OrderExecuted at ts T
    // for the same orderId — applying them in reverse would throw).
    private final PriorityQueue<TimedMessage> buffer =
            new PriorityQueue<>(
                    Comparator.comparingLong(TimedMessage::ts)
                              .thenComparingLong(TimedMessage::seq));
    private long readSequence = 0L;
    private long maxObservedTs = Long.MIN_VALUE;

    // Cached head, ready to yield
    private IexMessage next;
    private long nextTs;

    // Diagnostics
    private long messagesYielded;
    private long packetsRead;
    private long heartbeatPacketsSkipped;
    private long decodeFailures;

    private MessageStream(final PcapReader reader, final Decoder decoder,
                          final String label, final long reorderWindowNs) {
        this.reader = reader;
        this.decoder = decoder;
        this.label = label;
        this.reorderWindowNs = reorderWindowNs;
        this.nextTs = EXHAUSTED;
        advance();
    }

    public static MessageStream tops(final Path file) throws IOException {
        return new MessageStream(PcapReader.open(file), TopsMessageRouter::decode, "TOPS",
                DEFAULT_REORDER_WINDOW_NS);
    }

    public static MessageStream dpls(final Path file) throws IOException {
        return new MessageStream(PcapReader.open(file), DplsMessageRouter::decode, "DPLS",
                DEFAULT_REORDER_WINDOW_NS);
    }

    public static MessageStream deep(final Path file) throws IOException {
        return new MessageStream(PcapReader.open(file), DeepMessageRouter::decode, "DEEP",
                DEFAULT_REORDER_WINDOW_NS);
    }

    public static MessageStream tops(final Path file, final long reorderWindowNs) throws IOException {
        return new MessageStream(PcapReader.open(file), TopsMessageRouter::decode, "TOPS", reorderWindowNs);
    }

    public static MessageStream dpls(final Path file, final long reorderWindowNs) throws IOException {
        return new MessageStream(PcapReader.open(file), DplsMessageRouter::decode, "DPLS", reorderWindowNs);
    }

    public static MessageStream deep(final Path file, final long reorderWindowNs) throws IOException {
        return new MessageStream(PcapReader.open(file), DeepMessageRouter::decode, "DEEP", reorderWindowNs);
    }

    // ─── public API ──────────────────────────────────────────────────────────

    public long peekTs() {
        return nextTs;
    }

    public IexMessage peek() {
        return next;
    }

    public boolean isExhausted() {
        return next == null;
    }

    public String label() {
        return label;
    }

    public long messagesYielded() {
        return messagesYielded;
    }

    public long packetsRead() {
        return packetsRead;
    }

    public long heartbeatPacketsSkipped() {
        return heartbeatPacketsSkipped;
    }

    public long decodeFailures() {
        return decodeFailures;
    }

    public IexMessage consume() {
        IexMessage m = next;
        messagesYielded++;
        advance();
        return m;
    }

    // ─── advance: fill buffer then yield oldest safely-past message ──────────

    private void advance() {
        fillBufferUntilSafe();
        if (buffer.isEmpty()) {
            next = null;
            nextTs = EXHAUSTED;
            return;
        }
        TimedMessage tm = buffer.poll();
        next = tm.message;
        nextTs = tm.ts;
    }

    /**
     * Read messages into the buffer until either (a) the buffer's head is
     * older than {@code maxObservedTs - reorderWindowNs} (safely past, no
     * future message can have a smaller ts), or (b) the underlying reader
     * is exhausted (in which case we drain whatever's left).
     */
    private void fillBufferUntilSafe() {
        while (!readerExhausted) {
            if (!buffer.isEmpty()) {
                long headTs = buffer.peek().ts;
                if (headTs + reorderWindowNs <= maxObservedTs) {
                    // Head is safely past the window; nothing future can undercut it.
                    return;
                }
            }
            IexMessage m = readOneFromUnderlying();
            if (m == null) {
                readerExhausted = true;
                break;
            }
            long ts = m.timestampNanos();
            buffer.add(new TimedMessage(ts, readSequence++, m));
            if (ts > maxObservedTs) maxObservedTs = ts;
        }
        // Reader is done; subsequent calls drain the buffer to empty.
    }

    /**
     * Advance the underlying packet walker by one message; return the
     * decoded message or {@code null} at EOF. Heartbeats and decode failures
     * are skipped internally.
     */
    private IexMessage readOneFromUnderlying() {
        while (true) {
            // Try to pull the next message from the current packet.
            if (header != null && messageIndex < header.messageCount() && posInBlock + 2 <= endOfBlock) {
                int msgLen = Bytes.readShortLE(payload, posInBlock);
                posInBlock += 2;
                if (msgLen == 0 || posInBlock + msgLen > endOfBlock) {
                    header = null;
                    continue;
                }
                byte typeByte = payload[posInBlock];
                try {
                    IexMessage m = decoder.decode(typeByte, payload, posInBlock);
                    posInBlock += msgLen;
                    messageIndex++;
                    return m;
                } catch (IllegalArgumentException e) {
                    decodeFailures++;
                    posInBlock += msgLen;
                    messageIndex++;
                    continue;
                }
            }

            // Need a fresh packet.
            byte[] freshPayload;
            try {
                freshPayload = reader.nextUdpPayload();
            } catch (IOException e) {
                throw new RuntimeException("[" + label + "] pcap read failed", e);
            }
            if (freshPayload == null) {
                return null;
            }
            packetsRead++;
            if (freshPayload.length < IexTpDecoder.HEADER_BYTES) continue;
            IexTpDecoder.Header h = IexTpDecoder.decode(freshPayload);
            if (h.messageCount() == 0 || h.payloadLength() == 0) {
                heartbeatPacketsSkipped++;
                continue;
            }
            this.payload = freshPayload;
            this.header = h;
            this.messageIndex = 0;
            this.posInBlock = IexTpDecoder.HEADER_BYTES;
            this.endOfBlock = IexTpDecoder.HEADER_BYTES + h.payloadLength();
        }
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    private record TimedMessage(long ts, long seq, IexMessage message) {}
}
