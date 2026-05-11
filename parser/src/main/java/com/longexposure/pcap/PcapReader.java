package com.longexposure.pcap;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Streaming reader for IEX HIST .pcap.gz files. Handles both classic libpcap
 * and PCAP-NG (which is what current IEX HIST files actually use — the
 * Section Header Block magic 0x0A0D0D0A is the format discriminator).
 *
 * Implemented from scratch against the file-format specs (no pcap4j, no
 * libpcap native runtime). Reads directly from GZIPInputStream — no temp
 * file, no full decompression to disk.
 *
 * For each captured frame we strip Ethernet + IPv4 + UDP headers and yield
 * the UDP payload (the IEX-TP message block starting with the 40-byte header).
 * Non-IPv4-UDP frames are silently skipped.
 *
 * Usage:
 * <pre>{@code
 * try (PcapReader r = PcapReader.open(Path.of("/storage/raw/...pcap.gz"))) {
 *     byte[] payload;
 *     while ((payload = r.nextUdpPayload()) != null) {
 *         // payload starts with the 40-byte IEX-TP header
 *     }
 * }
 * }</pre>
 */
public final class PcapReader implements AutoCloseable {

    // Classic libpcap magic numbers (read in little-endian byte order).
    private static final int MAGIC_MICRO_LE = 0xa1b2c3d4;
    private static final int MAGIC_MICRO_BE = 0xd4c3b2a1;
    private static final int MAGIC_NANO_LE  = 0xa1b23c4d;
    private static final int MAGIC_NANO_BE  = 0x4d3cb2a1;

    // PCAP-NG Section Header Block magic (byte-order independent).
    private static final int PCAPNG_SHB_TYPE = 0x0a0d0d0a;
    private static final int PCAPNG_IDB_TYPE = 0x00000001;
    private static final int PCAPNG_EPB_TYPE = 0x00000006;
    private static final int PCAPNG_SPB_TYPE = 0x00000003;
    private static final int PCAPNG_BOM      = 0x1a2b3c4d;

    private static final int LINKTYPE_ETHERNET = 1;

    private static final int ETHERTYPE_IPV4 = 0x0800;
    private static final int IP_PROTO_UDP = 17;

    private static final int ETHERNET_HEADER_BYTES = 14;
    private static final int UDP_HEADER_BYTES = 8;

    enum Format { LIBPCAP, PCAPNG }

    private final DataInputStream in;
    private final ByteOrder byteOrder;
    private final Format format;
    private final boolean nanosecondTimestamps;
    private final Map<Integer, Integer> interfaceLinkTypes = new HashMap<>();
    private int firstLinkType = -1;

    private PcapReader(
            final DataInputStream in,
            final ByteOrder byteOrder,
            final Format format,
            final boolean nanosecondTimestamps,
            final int firstLinkType) {
        this.in = in;
        this.byteOrder = byteOrder;
        this.format = format;
        this.nanosecondTimestamps = nanosecondTimestamps;
        this.firstLinkType = firstLinkType;
    }

    /** Open a .pcap.gz file, auto-detect classic-vs-pcap-ng, and consume headers. */
    public static PcapReader open(final Path path) throws IOException {
        InputStream raw = Files.newInputStream(path);
        InputStream gz = new GZIPInputStream(new BufferedInputStream(raw, 1 << 16));
        PushbackInputStream pb = new PushbackInputStream(new BufferedInputStream(gz, 1 << 16), 8);

        byte[] sniff = pb.readNBytes(4);
        if (sniff.length != 4) {
            throw new IOException("File too short to detect pcap format");
        }
        int magicLe = ByteBuffer.wrap(sniff).order(ByteOrder.LITTLE_ENDIAN).getInt();
        pb.unread(sniff);

        DataInputStream stream = new DataInputStream(pb);

        if (magicLe == PCAPNG_SHB_TYPE) {
            return openPcapNg(stream);
        }
        if (magicLe == MAGIC_MICRO_LE || magicLe == MAGIC_NANO_LE
                || magicLe == MAGIC_MICRO_BE || magicLe == MAGIC_NANO_BE) {
            return openLibpcap(stream, magicLe);
        }
        throw new IOException(String.format(
                "Unrecognized pcap magic: 0x%08x (expected libpcap or pcap-ng SHB)", magicLe));
    }

    // ─── classic libpcap ─────────────────────────────────────────────────────

    private static PcapReader openLibpcap(final DataInputStream stream, final int magicLe) throws IOException {
        ByteOrder order;
        boolean nano;
        switch (magicLe) {
            case MAGIC_MICRO_LE -> { order = ByteOrder.LITTLE_ENDIAN; nano = false; }
            case MAGIC_NANO_LE  -> { order = ByteOrder.LITTLE_ENDIAN; nano = true;  }
            case MAGIC_MICRO_BE -> { order = ByteOrder.BIG_ENDIAN;    nano = false; }
            case MAGIC_NANO_BE  -> { order = ByteOrder.BIG_ENDIAN;    nano = true;  }
            default -> throw new IOException("Unreachable");
        }

        byte[] globalHeader = stream.readNBytes(24);
        if (globalHeader.length != 24) {
            throw new IOException("Truncated libpcap global header");
        }
        int linkType = ByteBuffer.wrap(globalHeader, 20, 4).order(order).getInt();
        if (linkType != LINKTYPE_ETHERNET) {
            throw new IOException("Unsupported link-layer type " + linkType
                    + " (expected " + LINKTYPE_ETHERNET + " = Ethernet)");
        }
        return new PcapReader(stream, order, Format.LIBPCAP, nano, linkType);
    }

    // ─── pcap-ng ─────────────────────────────────────────────────────────────

    private static PcapReader openPcapNg(final DataInputStream stream) throws IOException {
        // Read the Section Header Block.
        // 4 bytes already pushed back — re-read here.
        byte[] typeBytes = stream.readNBytes(4);
        int blockType = ByteBuffer.wrap(typeBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (blockType != PCAPNG_SHB_TYPE) {
            throw new IOException(String.format(
                    "pcap-ng must begin with a Section Header Block (got 0x%08x)", blockType));
        }
        // Block length is endian-dependent, but we haven't established endianness yet.
        // Read it twice — once each order — and use whichever gives a sane value once
        // we read the BOM. Simpler: read 4 bytes for length, 4 bytes for BOM, decide.
        byte[] lenAndBom = stream.readNBytes(8);
        if (lenAndBom.length != 8) {
            throw new IOException("Truncated SHB header");
        }
        int bomRaw = ByteBuffer.wrap(lenAndBom, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        ByteOrder order;
        if (bomRaw == PCAPNG_BOM) {
            order = ByteOrder.LITTLE_ENDIAN;
        } else if (Integer.reverseBytes(bomRaw) == PCAPNG_BOM) {
            order = ByteOrder.BIG_ENDIAN;
        } else {
            throw new IOException(String.format("Invalid pcap-ng byte-order magic: 0x%08x", bomRaw));
        }
        int totalLength = ByteBuffer.wrap(lenAndBom, 0, 4).order(order).getInt();
        if (totalLength < 28 || (totalLength & 0x3) != 0) {
            throw new IOException("Invalid SHB total length: " + totalLength);
        }
        // Skip the rest of the SHB body + trailing length: we've consumed 12 bytes
        // (type + length + BOM), so remaining is totalLength - 12.
        int remaining = totalLength - 12;
        long skipped = stream.skip(remaining);
        if (skipped != remaining) {
            throw new IOException("Short skip in SHB: wanted " + remaining + " got " + skipped);
        }

        PcapReader r = new PcapReader(stream, order, Format.PCAPNG, true, -1);
        return r;
    }

    // ─── public API ──────────────────────────────────────────────────────────

    /** Next UDP payload (IEX-TP message block), or {@code null} at end of stream. */
    public byte[] nextUdpPayload() throws IOException {
        return switch (format) {
            case LIBPCAP -> nextUdpPayloadLibpcap();
            case PCAPNG  -> nextUdpPayloadPcapNg();
        };
    }

    public boolean isNanosecondTimestamps() {
        return nanosecondTimestamps;
    }

    public ByteOrder byteOrder() {
        return byteOrder;
    }

    public int linkLayerType() {
        return firstLinkType;
    }

    public Format format() {
        return format;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    // ─── libpcap record loop ─────────────────────────────────────────────────

    private byte[] nextUdpPayloadLibpcap() throws IOException {
        while (true) {
            byte[] recordHeader = new byte[16];
            try {
                in.readFully(recordHeader);
            } catch (EOFException eof) {
                return null;
            }
            ByteBuffer rh = ByteBuffer.wrap(recordHeader).order(byteOrder);
            rh.position(8);
            int inclLen = rh.getInt();
            int origLen = rh.getInt();
            if (inclLen < 0 || inclLen > (1 << 24)) {
                throw new IOException("Suspicious libpcap record length: " + inclLen);
            }
            byte[] frame = in.readNBytes(inclLen);
            if (frame.length != inclLen) {
                throw new IOException("Truncated packet record");
            }
            byte[] payload = extractUdpPayload(frame, origLen);
            if (payload != null) return payload;
        }
    }

    // ─── pcap-ng block loop ──────────────────────────────────────────────────

    private byte[] nextUdpPayloadPcapNg() throws IOException {
        while (true) {
            byte[] header = new byte[8];
            try {
                in.readFully(header);
            } catch (EOFException eof) {
                return null;
            }
            int blockType = ByteBuffer.wrap(header, 0, 4).order(byteOrder).getInt();
            int blockTotalLength = ByteBuffer.wrap(header, 4, 4).order(byteOrder).getInt();
            if (blockTotalLength < 12 || (blockTotalLength & 0x3) != 0) {
                throw new IOException("Invalid pcap-ng block length " + blockTotalLength
                        + " (type=0x" + Integer.toHexString(blockType) + ")");
            }
            int bodyLen = blockTotalLength - 12; // 8 bytes header + 4 bytes trailing length

            byte[] body = in.readNBytes(bodyLen);
            if (body.length != bodyLen) {
                throw new IOException("Truncated pcap-ng block body");
            }
            // Trailing block-total-length: consume + verify.
            byte[] trailing = in.readNBytes(4);
            if (trailing.length != 4) {
                throw new IOException("Truncated pcap-ng block trailer");
            }
            int trailingLen = ByteBuffer.wrap(trailing).order(byteOrder).getInt();
            if (trailingLen != blockTotalLength) {
                throw new IOException("pcap-ng block length mismatch: head=" + blockTotalLength
                        + " tail=" + trailingLen + " type=0x" + Integer.toHexString(blockType));
            }

            switch (blockType) {
                case PCAPNG_IDB_TYPE -> handleInterfaceDescription(body);
                case PCAPNG_EPB_TYPE -> {
                    byte[] payload = handleEnhancedPacket(body);
                    if (payload != null) return payload;
                }
                case PCAPNG_SPB_TYPE -> {
                    byte[] payload = handleSimplePacket(body);
                    if (payload != null) return payload;
                }
                case PCAPNG_SHB_TYPE -> {
                    // A new section begins. Spec allows this; for our use case
                    // we treat it like an opaque marker (re-establishing endianness
                    // would be possible but we expect a single section in HIST).
                }
                default -> { /* skip any other block type */ }
            }
        }
    }

    private void handleInterfaceDescription(final byte[] body) {
        // IDB body: linkType(2) reserved(2) snapLen(4) options...
        int linkType = ByteBuffer.wrap(body, 0, 2).order(byteOrder).getShort() & 0xffff;
        int ifaceId = interfaceLinkTypes.size();
        interfaceLinkTypes.put(ifaceId, linkType);
        if (firstLinkType < 0) {
            firstLinkType = linkType;
        }
    }

    private byte[] handleEnhancedPacket(final byte[] body) throws IOException {
        // EPB body: ifaceId(4) tsHigh(4) tsLow(4) capLen(4) origLen(4) data(padded) options
        if (body.length < 20) {
            throw new IOException("EPB body too short: " + body.length);
        }
        int ifaceId = ByteBuffer.wrap(body, 0, 4).order(byteOrder).getInt();
        int capLen = ByteBuffer.wrap(body, 12, 4).order(byteOrder).getInt();
        int origLen = ByteBuffer.wrap(body, 16, 4).order(byteOrder).getInt();
        if (capLen < 0 || 20 + capLen > body.length) {
            throw new IOException("EPB captured length " + capLen + " out of bounds (body " + body.length + ")");
        }
        // Verify the interface uses an Ethernet link layer (else skip).
        Integer linkType = interfaceLinkTypes.get(ifaceId);
        if (linkType != null && linkType != LINKTYPE_ETHERNET) {
            return null;
        }
        byte[] frame = new byte[capLen];
        System.arraycopy(body, 20, frame, 0, capLen);
        return extractUdpPayload(frame, origLen);
    }

    private byte[] handleSimplePacket(final byte[] body) throws IOException {
        // SPB body: origLen(4) data(padded)
        if (body.length < 4) {
            throw new IOException("SPB body too short");
        }
        int origLen = ByteBuffer.wrap(body, 0, 4).order(byteOrder).getInt();
        int capLen = Math.min(origLen, body.length - 4);
        byte[] frame = new byte[capLen];
        System.arraycopy(body, 4, frame, 0, capLen);
        return extractUdpPayload(frame, origLen);
    }

    // ─── shared Ethernet/IPv4/UDP strip ──────────────────────────────────────

    private static byte[] extractUdpPayload(final byte[] frame, final int origLen) throws IOException {
        if (frame.length < ETHERNET_HEADER_BYTES) {
            return null;
        }
        int ethertype = ByteBuffer.wrap(frame, 12, 2)
                .order(ByteOrder.BIG_ENDIAN).getShort() & 0xffff;
        if (ethertype != ETHERTYPE_IPV4) {
            return null;
        }
        int ipStart = ETHERNET_HEADER_BYTES;
        if (frame.length < ipStart + 20) {
            return null;
        }
        int versionIhl = frame[ipStart] & 0xff;
        int version = versionIhl >>> 4;
        int ihl = versionIhl & 0x0f;
        if (version != 4) {
            return null;
        }
        int ipHeaderBytes = ihl * 4;
        if (ipHeaderBytes < 20) {
            throw new IOException("Invalid IHL " + ihl + " (header < 20 bytes)");
        }
        int protocol = frame[ipStart + 9] & 0xff;
        if (protocol != IP_PROTO_UDP) {
            return null;
        }
        int udpStart = ipStart + ipHeaderBytes;
        if (frame.length < udpStart + UDP_HEADER_BYTES) {
            return null;
        }
        int udpLength = ByteBuffer.wrap(frame, udpStart + 4, 2)
                .order(ByteOrder.BIG_ENDIAN).getShort() & 0xffff;
        int payloadLength = udpLength - UDP_HEADER_BYTES;
        int payloadStart = udpStart + UDP_HEADER_BYTES;
        if (payloadLength < 0 || payloadStart + payloadLength > frame.length) {
            throw new IOException("UDP length " + udpLength + " inconsistent with captured "
                    + (frame.length - udpStart) + " bytes (origLen=" + origLen + ")");
        }
        byte[] payload = new byte[payloadLength];
        System.arraycopy(frame, payloadStart, payload, 0, payloadLength);
        return payload;
    }
}
