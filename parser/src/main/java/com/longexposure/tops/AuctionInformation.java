package com.longexposure.tops;

import com.longexposure.wire.Bytes;

/**
 * Auction Information Message — {@code A} (0x41), 80 bytes.
 *
 * <p>Broadcast every second between Lock-in Time and the auction match for
 * Opening/Closing Auctions, and during the Display Only Period for IPO,
 * Halt, and Volatility Auctions. IEX-listed securities only.
 *
 * <pre>
 * Offset  Size  Field
 *     0     1   Message Type (0x41)
 *     1     1   Auction Type (O / C / I / H / V)
 *     2     8   Timestamp
 *    10     8   Symbol
 *    18     4   Paired Shares
 *    22     8   Reference Price
 *    30     8   Indicative Clearing Price
 *    38     4   Imbalance Shares
 *    42     1   Imbalance Side (B / S / N)
 *    43     1   Extension Number
 *    44     4   Scheduled Auction Time (Event Time — seconds since POSIX epoch UTC)
 *    48     8   Auction Book Clearing Price
 *    56     8   Collar Reference Price
 *    64     8   Lower Auction Collar
 *    72     8   Upper Auction Collar
 * </pre>
 */
public record AuctionInformation(
        AuctionType auctionType,
        long timestampNanos,
        String symbol,
        int pairedShares,
        long referencePriceRaw,
        long indicativeClearingPriceRaw,
        int imbalanceShares,
        ImbalanceSide imbalanceSide,
        int extensionNumber,
        long scheduledAuctionTimeEpochSeconds,
        long auctionBookClearingPriceRaw,
        long collarReferencePriceRaw,
        long lowerAuctionCollarRaw,
        long upperAuctionCollarRaw) implements TopsMessage {

    public static final byte MESSAGE_TYPE = (byte) 0x41;
    public static final int BYTE_LENGTH = 80;

    @Override
    public byte messageType() {
        return MESSAGE_TYPE;
    }

    public static AuctionInformation decode(final byte[] buf, final int offset) {
        Bytes.requireLength(buf, offset, BYTE_LENGTH, "AuctionInformation");
        AuctionType type  = AuctionType.fromByte(buf[offset + 1]);
        long ts           = Bytes.readLongLE(buf, offset + 2);
        String symbol     = Bytes.decodeSymbol(buf, offset + 10);
        int paired        = Bytes.readIntLE(buf, offset + 18);
        long refPrice     = Bytes.readLongLE(buf, offset + 22);
        long indClearing  = Bytes.readLongLE(buf, offset + 30);
        int imbalance     = Bytes.readIntLE(buf, offset + 38);
        ImbalanceSide side = ImbalanceSide.fromByte(buf[offset + 42]);
        int extension     = buf[offset + 43] & 0xff;
        long schedTime    = ((long) Bytes.readIntLE(buf, offset + 44)) & 0xffffffffL;
        long bookClearing = Bytes.readLongLE(buf, offset + 48);
        long collarRef    = Bytes.readLongLE(buf, offset + 56);
        long lowerCollar  = Bytes.readLongLE(buf, offset + 64);
        long upperCollar  = Bytes.readLongLE(buf, offset + 72);
        return new AuctionInformation(type, ts, symbol, paired, refPrice, indClearing,
                imbalance, side, extension, schedTime,
                bookClearing, collarRef, lowerCollar, upperCollar);
    }

    public double referencePrice()          { return referencePriceRaw          / 10_000.0; }
    public double indicativeClearingPrice() { return indicativeClearingPriceRaw / 10_000.0; }
    public double auctionBookClearingPrice(){ return auctionBookClearingPriceRaw/ 10_000.0; }
    public double collarReferencePrice()    { return collarReferencePriceRaw    / 10_000.0; }
    public double lowerAuctionCollar()      { return lowerAuctionCollarRaw      / 10_000.0; }
    public double upperAuctionCollar()      { return upperAuctionCollarRaw      / 10_000.0; }

    public enum AuctionType {
        OPENING((byte) 0x4f),     // 'O'
        CLOSING((byte) 0x43),     // 'C'
        IPO((byte) 0x49),         // 'I'
        HALT((byte) 0x48),        // 'H'
        VOLATILITY((byte) 0x56);  // 'V'

        public final byte value;

        AuctionType(final byte value) {
            this.value = value;
        }

        public static AuctionType fromByte(final byte b) {
            for (AuctionType a : values()) {
                if (a.value == b) return a;
            }
            throw new IllegalArgumentException(
                    String.format("Unknown AuctionType: 0x%02x", b & 0xff));
        }
    }

    public enum ImbalanceSide {
        BUY((byte) 0x42),  // 'B'
        SELL((byte) 0x53), // 'S'
        NONE((byte) 0x4e); // 'N'

        public final byte value;

        ImbalanceSide(final byte value) {
            this.value = value;
        }

        public static ImbalanceSide fromByte(final byte b) {
            for (ImbalanceSide s : values()) {
                if (s.value == b) return s;
            }
            throw new IllegalArgumentException(
                    String.format("Unknown ImbalanceSide: 0x%02x", b & 0xff));
        }
    }
}
