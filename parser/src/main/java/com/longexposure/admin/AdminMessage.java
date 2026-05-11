package com.longexposure.admin;

import com.longexposure.wire.IexMessage;

/**
 * Marker interface for the seven IEX administrative messages that are
 * byte-identical across TOPS, DEEP, and DPLS feeds. Decoders for these
 * messages live in this package and are reused unchanged when the project
 * extends from TOPS (v1) to DPLS (phase 2).
 *
 * <p>The "Security Event" message ({@code E}, 0x45) is carried by DEEP and
 * DPLS but not TOPS — however the wire format is identical when present,
 * so the record lives in this same package.
 *
 * <p>Sealed so consumers can switch exhaustively without a default branch.
 *
 * <pre>{@code
 * AdminMessage m = AdminMessages.decode(typeByte, buf, offset);
 * String s = switch (m) {
 *     case SystemEvent e          -> "system: " + e.event();
 *     case SecurityDirectory d    -> "directory: " + d.symbol();
 *     case TradingStatus h        -> "status: "    + h.symbol() + " " + h.status();
 *     case RetailLiquidityIndicator i -> "rli: " + i.symbol();
 *     case OperationalHaltStatus o -> "ophalt: " + o.symbol();
 *     case ShortSalePriceTestStatus p -> "sspt: " + p.symbol();
 *     case SecurityEvent se       -> "sec: " + se.event();
 * };
 * }</pre>
 */
public sealed interface AdminMessage extends IexMessage permits
        SystemEvent,
        SecurityDirectory,
        TradingStatus,
        RetailLiquidityIndicator,
        OperationalHaltStatus,
        ShortSalePriceTestStatus,
        SecurityEvent {
}
