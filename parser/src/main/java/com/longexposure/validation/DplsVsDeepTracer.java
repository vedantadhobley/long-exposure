package com.longexposure.validation;

import com.longexposure.deep.PriceLevelUpdate;
import com.longexposure.dpls.AddOrder;
import com.longexposure.dpls.ClearBook;
import com.longexposure.dpls.OrderBook;
import com.longexposure.dpls.OrderBookManager;
import com.longexposure.dpls.OrderDelete;
import com.longexposure.dpls.OrderExecuted;
import com.longexposure.dpls.OrderModify;
import com.longexposure.wire.IexMessage;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Single-symbol, narrow-time-window trace of merged DPLS and DEEP
 * events. Used to inspect specific mismatches that
 * {@link DeepVsDplsValidator} surfaces — prints each DPLS event
 * applied to our order-level book, each DEEP PLU applied to the
 * price-level (or just compared against DPLS aggregate), and at
 * each transaction-complete PLU the side-by-side aggregate vs
 * stated-size comparison so the divergence point is obvious.
 *
 * <p>Merge rule mirrors {@link DeepVsDplsValidator}: DPLS wins on
 * timestamp ties so its order-level events apply before DEEP's
 * level update at the same nanosecond.
 */
public final class DplsVsDeepTracer {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSSSSS").withZone(ZoneOffset.UTC);

    private final String targetSymbol;
    private final long windowStartNanos;
    private final long windowEndNanos;
    private final long targetTsNanos;
    private final OrderBookManager dplsBookMgr = new OrderBookManager();
    private final PrintStream out;
    private int linesEmitted;

    public DplsVsDeepTracer(final String symbol, final long targetTsNanos,
                            final long windowMs, final PrintStream out) {
        this.targetSymbol = symbol;
        this.targetTsNanos = targetTsNanos;
        long windowNanos = windowMs * 1_000_000L;
        this.windowStartNanos = targetTsNanos - windowNanos;
        this.windowEndNanos = targetTsNanos + windowNanos;
        this.out = out;
    }

    public void run(final Path dplsFile, final Path deepFile) throws IOException {
        out.printf("== trace: symbol=%s  target=%s  window=±%dms ==%n",
                targetSymbol, formatNanos(targetTsNanos),
                (windowEndNanos - windowStartNanos) / 2 / 1_000_000L);
        out.printf("window: %s  →  %s%n",
                formatNanos(windowStartNanos), formatNanos(windowEndNanos));
        out.println();

        try (MessageStream dpls = MessageStream.dpls(dplsFile);
             MessageStream deep = MessageStream.deep(deepFile)) {

            while (!dpls.isExhausted() || !deep.isExhausted()) {
                long dplsTs = dpls.peekTs();
                long deepTs = deep.peekTs();

                // Bulk-skip before the window — still apply DPLS to the book
                // so we have correct state when we hit the window.
                if (dplsTs < windowStartNanos && deepTs < windowStartNanos) {
                    if (dplsTs <= deepTs) {
                        IexMessage m = dpls.consume();
                        applyDpls(m);
                    } else {
                        deep.consume();
                    }
                    continue;
                }

                if (dplsTs > windowEndNanos && deepTs > windowEndNanos) break;

                if (dplsTs <= deepTs) {
                    IexMessage m = dpls.consume();
                    applyDpls(m);
                    if (inWindow(dplsTs) && symbolOf(m).equals(targetSymbol)) {
                        printDpls(dplsTs, m);
                    }
                } else {
                    IexMessage m = deep.consume();
                    if (m instanceof PriceLevelUpdate plu
                            && inWindow(deepTs) && plu.symbol().equals(targetSymbol)) {
                        printDeep(deepTs, plu);
                    }
                }
            }
        }

        out.println();
        out.println("== trace complete (" + linesEmitted + " lines emitted) ==");
    }

    private boolean inWindow(final long ts) {
        return ts >= windowStartNanos && ts <= windowEndNanos;
    }

    private void applyDpls(final IexMessage m) {
        dplsBookMgr.apply(m);
    }

    private static String symbolOf(final IexMessage m) {
        return switch (m) {
            case AddOrder a -> a.symbol();
            case OrderModify om -> om.symbol();
            case OrderDelete od -> od.symbol();
            case OrderExecuted oe -> oe.symbol();
            case ClearBook cb -> cb.symbol();
            default -> "";
        };
    }

    private void printDpls(final long ts, final IexMessage m) {
        out.printf("[%s] DPLS  %s%n", formatNanos(ts), describe(m));
        linesEmitted++;
    }

    private void printDeep(final long ts, final PriceLevelUpdate plu) {
        OrderBook book = dplsBookMgr.book(targetSymbol);
        AddOrder.Side side = (plu.side() == PriceLevelUpdate.Side.BUY)
                ? AddOrder.Side.BUY : AddOrder.Side.SELL;
        long dplsAgg = book == null ? 0L : book.aggregateAt(side, plu.priceRaw());
        long deepSize = plu.size();
        String flagStr = plu.isTransactionComplete() ? "TXN-COMPLETE" : "mid-txn";
        String mark = plu.isTransactionComplete()
                ? (dplsAgg == deepSize ? "MATCH" : "MISMATCH")
                : "—";

        out.printf("[%s] DEEP  PLU side=%s price=%d size=%d  flag=%s  [%s]%n",
                formatNanos(ts),
                plu.side(), plu.priceRaw(), plu.size(), flagStr, mark);
        if (plu.isTransactionComplete()) {
            out.printf("           dpls_aggregate=%d  deep_size=%d  delta=%+d%n",
                    dplsAgg, deepSize, dplsAgg - deepSize);
        }
        linesEmitted++;
    }

    private static String describe(final IexMessage m) {
        return switch (m) {
            case AddOrder a -> String.format("AddOrder      %s  side=%s  id=%d  %d @ %d",
                    a.symbol(), a.side(), a.orderId(), a.size(), a.priceRaw());
            case OrderModify om -> String.format("OrderModify   %s  id=%d  size=%d  price=%d  prio=%s",
                    om.symbol(), om.orderId(), om.size(), om.priceRaw(),
                    om.maintainPriority() ? "maintain" : "reset");
            case OrderDelete od -> String.format("OrderDelete   %s  id=%d",
                    od.symbol(), od.orderId());
            case OrderExecuted oe -> String.format("OrderExecuted %s  id=%d  %d @ %d  tradeId=%d",
                    oe.symbol(), oe.orderId(), oe.size(), oe.priceRaw(), oe.tradeId());
            case ClearBook cb -> String.format("ClearBook     %s", cb.symbol());
            default -> m.getClass().getSimpleName();
        };
    }

    private static String formatNanos(final long nanosSinceEpoch) {
        long seconds = nanosSinceEpoch / 1_000_000_000L;
        long nanos = nanosSinceEpoch % 1_000_000_000L;
        if (nanos < 0) { nanos += 1_000_000_000L; seconds -= 1; }
        return TS_FMT.format(Instant.ofEpochSecond(seconds, nanos));
    }
}
