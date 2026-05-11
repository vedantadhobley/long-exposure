package com.longexposure.deep;

import com.longexposure.wire.Bytes;

/**
 * Price Level Update — {@code 8} (0x38) for the buy side or {@code 5} (0x35)
 * for the sell side. 30 bytes either way.
 *
 * <p>Reports the new aggregate displayed size at a single price level on
 * one side of the book. {@code size == 0} means "remove this price level
 * entirely" (the previous occupants are all gone).
 *
 * <pre>
 * Offset  Size  Field
 *     0     1   Message Type (0x38 buy / 0x35 sell)
 *     1     1   Event Flags (bit 0: 0 = mid-transaction, 1 = transaction complete)
 *     2     8   Timestamp
 *    10     8   Symbol
 *    18     4   Size  (aggregate displayed shares; 0 = level removed)
 *    22     8   Price
 * </pre>
 *
 * <p><b>Atomic-transaction rule.</b> A single matching-engine event can
 * change multiple price levels at once. IEX serializes that as a run of
 * PLUs with event flag {@code 0x00} (mid-transaction) followed by a final
 * PLU with flag {@code 0x01} (transaction complete). Consumers deriving
 * BBO from DEEP <strong>must hold the previous BBO while the event flag
 * is 0</strong> and only recompute on a {@code 0x01} message — the
 * intermediate book states the {@code 0x00} messages momentarily produce
 * never "really existed" at the engine. A single-level change carries
 * {@code 0x01} directly with no preceding {@code 0x00} run. A
 * {@code TradeReport} can interleave between an opening {@code 0x00} and
 * its closing {@code 0x01}; it is part of the same transaction.
 */
public record PriceLevelUpdate(
        Side side,
        byte eventFlags,
        long timestampNanos,
        String symbol,
        int size,
        long priceRaw) implements DeepMessage {

    public static final byte MESSAGE_TYPE_BUY  = (byte) 0x38;
    public static final byte MESSAGE_TYPE_SELL = (byte) 0x35;
    public static final int BYTE_LENGTH = 30;

    /** Event Flags bit 0: 1 = transaction complete, 0 = mid-transaction. */
    private static final int FLAG_TRANSACTION_COMPLETE = 0x01;

    public enum Side { BUY, SELL }

    @Override
    public byte messageType() {
        return side == Side.BUY ? MESSAGE_TYPE_BUY : MESSAGE_TYPE_SELL;
    }

    /**
     * Decode a PLU body. Caller has already identified the side from the
     * 1-byte type at {@code buf[offset]}; we don't re-read it.
     */
    public static PriceLevelUpdate decode(final Side side, final byte[] buf, final int offset) {
        Bytes.requireLength(buf, offset, BYTE_LENGTH, "PriceLevelUpdate");
        byte flags    = buf[offset + 1];
        long ts       = Bytes.readLongLE(buf, offset + 2);
        String symbol = Bytes.decodeSymbol(buf, offset + 10);
        int size      = Bytes.readIntLE(buf, offset + 18);
        long priceRaw = Bytes.readLongLE(buf, offset + 22);
        return new PriceLevelUpdate(side, flags, ts, symbol, size, priceRaw);
    }

    public boolean isTransactionComplete() {
        return (eventFlags & FLAG_TRANSACTION_COMPLETE) != 0;
    }

    public double price() {
        return priceRaw / 10_000.0;
    }
}
