package com.longexposure.validation;

import com.longexposure.deepplus.DeepPlusMessageRouter;
import com.longexposure.pcap.PcapReader;
import com.longexposure.tops.TopsMessageRouter;
import com.longexposure.transport.IexTpDecoder;
import com.longexposure.wire.Bytes;
import com.longexposure.wire.IexMessage;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Peekable, time-ordered stream of decoded {@link IexMessage}s from a single
 * .pcap.gz HIST file. Wraps a {@link PcapReader} plus a feed-specific
 * decoder (TOPS or DEEP+).
 *
 * <p>Used to merge two feeds' message streams in
 * {@link BboCrossValidator} — peek both, advance the earlier-timestamp one,
 * compare on TOPS QuoteUpdate.
 *
 * <p>Heartbeat packets (IEX-TP {@code payloadLength == 0}) are silently
 * skipped. Decode failures on individual messages are also skipped — they're
 * logged to stderr but don't stop the stream.
 *
 * <p>Not thread-safe.
 */
public final class MessageStream implements AutoCloseable {

    /** Decoder = (typeByte, buf, offset) -> decoded message. Throws on unknown types. */
    @FunctionalInterface
    public interface Decoder {
        IexMessage decode(byte typeByte, byte[] buf, int offset);
    }

    /** Sentinel value returned by {@link #peekTs()} once the stream is drained. */
    public static final long EXHAUSTED = Long.MAX_VALUE;

    private final PcapReader reader;
    private final Decoder decoder;
    private final String label;

    private byte[] payload;
    private IexTpDecoder.Header header;
    private int messageIndex;        // next message index within current packet
    private int posInBlock;          // byte offset in payload for the next msg's length prefix
    private int endOfBlock;

    private IexMessage next;         // decoded next message, ready to consume
    private long nextTs;
    private long messagesYielded;
    private long heartbeatPacketsSkipped;
    private long packetsRead;
    private long decodeFailures;

    private MessageStream(final PcapReader reader, final Decoder decoder, final String label) {
        this.reader = reader;
        this.decoder = decoder;
        this.label = label;
        this.nextTs = EXHAUSTED;
        advance();
    }

    public static MessageStream tops(final Path file) throws IOException {
        return new MessageStream(PcapReader.open(file), TopsMessageRouter::decode, "TOPS");
    }

    public static MessageStream deepPlus(final Path file) throws IOException {
        return new MessageStream(PcapReader.open(file), DeepPlusMessageRouter::decode, "DEEP+");
    }

    /** Next message's timestamp, or {@link #EXHAUSTED} if drained. */
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

    /**
     * Consume and return the current head. Advances the stream. Caller must
     * check {@link #isExhausted()} or {@link #peekTs()} before calling
     * if there might be no more messages.
     */
    public IexMessage consume() {
        IexMessage m = next;
        messagesYielded++;
        advance();
        return m;
    }

    private void advance() {
        while (true) {
            // Try to pull the next message from the current packet.
            if (header != null && messageIndex < header.messageCount() && posInBlock + 2 <= endOfBlock) {
                int msgLen = Bytes.readShortLE(payload, posInBlock);
                posInBlock += 2;
                if (msgLen == 0 || posInBlock + msgLen > endOfBlock) {
                    // Bad framing — give up on the rest of this packet.
                    header = null;
                    continue;
                }
                byte typeByte = payload[posInBlock];
                try {
                    next = decoder.decode(typeByte, payload, posInBlock);
                    nextTs = next.timestampNanos();
                    posInBlock += msgLen;
                    messageIndex++;
                    return;
                } catch (IllegalArgumentException e) {
                    decodeFailures++;
                    posInBlock += msgLen;
                    messageIndex++;
                    continue;
                }
            }

            // Need a fresh packet.
            byte[] payload;
            try {
                payload = reader.nextUdpPayload();
            } catch (IOException e) {
                throw new RuntimeException("[" + label + "] pcap read failed", e);
            }
            if (payload == null) {
                // End of file.
                next = null;
                nextTs = EXHAUSTED;
                return;
            }
            packetsRead++;
            if (payload.length < IexTpDecoder.HEADER_BYTES) continue;
            IexTpDecoder.Header h = IexTpDecoder.decode(payload);
            if (h.messageCount() == 0 || h.payloadLength() == 0) {
                heartbeatPacketsSkipped++;
                continue;
            }
            this.payload = payload;
            this.header = h;
            this.messageIndex = 0;
            this.posInBlock = IexTpDecoder.HEADER_BYTES;
            this.endOfBlock = IexTpDecoder.HEADER_BYTES + h.payloadLength();
            // loop around to decode the first message of this packet
        }
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
