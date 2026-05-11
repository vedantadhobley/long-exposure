package com.longexposure.deep;

import com.longexposure.admin.AdminMessage;
import com.longexposure.admin.AdminMessages;
import com.longexposure.tops.AuctionInformation;
import com.longexposure.tops.OfficialPrice;
import com.longexposure.tops.TradeBreak;
import com.longexposure.tops.TradeReport;
import com.longexposure.wire.IexMessage;

/**
 * Decode a DEEP message (admin, DEEP-unique, or shared-with-TOPS) by its
 * 1-byte type identifier.
 *
 * <p>Dispatch order:
 * <ol>
 *   <li>Admin messages first via {@link AdminMessages#decode} —
 *       byte-identical across all three IEX feeds.
 *   <li>DEEP-unique: {@link PriceLevelUpdate} for {@code 8} (buy) and
 *       {@code 5} (sell).
 *   <li>Shared with TOPS: {@code T} {@link TradeReport}, {@code B}
 *       {@link TradeBreak}, {@code X} {@link OfficialPrice}, {@code A}
 *       {@link AuctionInformation}. These records live under
 *       {@code com.longexposure.tops} as a historical artifact of build
 *       order (TOPS came first) — they are byte-identical on the DEEP
 *       wire and reused unchanged. A future refactor could move them
 *       to a shared package; the cross-package import keeps it honest
 *       in the meantime.
 * </ol>
 *
 * <p>Unknown types throw — DEEP 1.08 reserves room to grow but every type
 * we see in HIST should be one of these.
 */
public final class DeepMessageRouter {

    private DeepMessageRouter() {}

    public static IexMessage decode(final byte messageType, final byte[] buf, final int offset) {
        AdminMessage admin = AdminMessages.decode(messageType, buf, offset);
        if (admin != null) {
            return admin;
        }
        return switch (messageType) {
            case PriceLevelUpdate.MESSAGE_TYPE_BUY  -> PriceLevelUpdate.decode(PriceLevelUpdate.Side.BUY, buf, offset);
            case PriceLevelUpdate.MESSAGE_TYPE_SELL -> PriceLevelUpdate.decode(PriceLevelUpdate.Side.SELL, buf, offset);
            case TradeReport.MESSAGE_TYPE           -> TradeReport.decode(buf, offset);
            case TradeBreak.MESSAGE_TYPE            -> TradeBreak.decode(buf, offset);
            case OfficialPrice.MESSAGE_TYPE         -> OfficialPrice.decode(buf, offset);
            case AuctionInformation.MESSAGE_TYPE    -> AuctionInformation.decode(buf, offset);
            default -> throw new IllegalArgumentException(
                    String.format("Unknown DEEP message type: 0x%02x", messageType & 0xff));
        };
    }

    /** True if {@code messageType} is a DEEP-feed trading type (DEEP-unique or shared). */
    public static boolean isDeepTradingType(final byte messageType) {
        return switch (messageType) {
            case PriceLevelUpdate.MESSAGE_TYPE_BUY,
                 PriceLevelUpdate.MESSAGE_TYPE_SELL,
                 TradeReport.MESSAGE_TYPE,
                 TradeBreak.MESSAGE_TYPE,
                 OfficialPrice.MESSAGE_TYPE,
                 AuctionInformation.MESSAGE_TYPE -> true;
            default -> false;
        };
    }
}
