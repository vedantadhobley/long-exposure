package com.longexposure.wire;

/**
 * Sale Condition Flags bitfield — shared across TOPS Trade Report,
 * TOPS Trade Break, DPLS Order Executed, DPLS Trade, DPLS Trade Break.
 * TOPS 1.66 Appendix A and DPLS 1.02 Appendix A define the same layout.
 *
 * <pre>
 *  Bit  Mask  Name                            Set means...
 *   7   0x80  F: Intermarket Sweep Flag       Trade resulted from an ISO
 *   6   0x40  T: Extended Hours Flag          Form T (pre/post-market)
 *   5   0x20  I: Odd Lot Flag                 Less than one round lot
 *   4   0x10  8: Trade Through Exempt Flag    Not subject to Rule 611
 *   3   0x08  X: Single-price Cross Flag      Single-price cross
 * </pre>
 *
 * <p>Trade-eligibility rules (Last Sale Eligible, High/Low Eligible,
 * Volume Eligible) are derived combinations of these flags — see spec
 * Appendix A. The scorer will care about a subset:
 * <ul>
 *   <li>Volume sums need Volume-Eligible filtering
 *   <li>High/low marks need High/Low-Eligible filtering
 *   <li>Cross trades (X bit) often signal opening/closing-auction prints
 * </ul>
 */
public final class SaleConditionFlags {

    public static final int F_INTERMARKET_SWEEP   = 0x80;
    public static final int F_EXTENDED_HOURS      = 0x40;
    public static final int F_ODD_LOT             = 0x20;
    public static final int F_TRADE_THROUGH_EXEMPT = 0x10;
    public static final int F_SINGLE_PRICE_CROSS  = 0x08;

    private final byte flags;

    public SaleConditionFlags(final byte flags) {
        this.flags = flags;
    }

    public byte raw() { return flags; }

    public boolean isIntermarketSweep()  { return (flags & F_INTERMARKET_SWEEP)    != 0; }
    public boolean isExtendedHours()     { return (flags & F_EXTENDED_HOURS)       != 0; }
    public boolean isOddLot()            { return (flags & F_ODD_LOT)              != 0; }
    public boolean isTradeThroughExempt(){ return (flags & F_TRADE_THROUGH_EXEMPT) != 0; }
    public boolean isSinglePriceCross()  { return (flags & F_SINGLE_PRICE_CROSS)   != 0; }

    /** Last-sale eligibility per Appendix A: not Odd Lot, not Extended Hours. */
    public boolean isLastSaleEligible() {
        return !isExtendedHours() && !isOddLot();
    }

    /** High/Low eligibility per Appendix A: not Odd Lot, not Extended Hours, not Single-price Cross. */
    public boolean isHighLowEligible() {
        return !isExtendedHours() && !isOddLot() && !isSinglePriceCross();
    }

    /** Volume eligibility per Appendix A: every trade contributes. */
    public boolean isVolumeEligible() {
        return true;
    }
}
