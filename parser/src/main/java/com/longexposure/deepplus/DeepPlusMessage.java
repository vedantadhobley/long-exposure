package com.longexposure.deepplus;

import com.longexposure.wire.IexMessage;

/**
 * Sealed marker for the 7 DEEP+ feed-specific trading messages.
 * Distinct from the 7 byte-identical admin messages in
 * {@code com.longexposure.admin} (which are reused as-is across feeds).
 *
 * <p>DEEP+ is order-by-order: where TOPS gives you best bid/ask and
 * executed trades, DEEP+ gives you the full lifecycle of every individual
 * displayed order — added to the book, modified, deleted, or executed.
 *
 * <p>Use {@link DeepPlusMessageRouter#decode(byte, byte[], int)} to dispatch
 * any DEEP+ message (admin or trading) by 1-byte type identifier.
 */
public sealed interface DeepPlusMessage extends IexMessage permits
        AddOrder,
        OrderModify,
        OrderDelete,
        OrderExecuted,
        Trade,
        TradeBreak,
        ClearBook {
}
