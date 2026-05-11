package com.longexposure.validation;

import com.longexposure.dpls.AddOrder;
import com.longexposure.dpls.ClearBook;
import com.longexposure.dpls.OrderBook;
import com.longexposure.dpls.OrderBookManager;
import com.longexposure.dpls.OrderDelete;
import com.longexposure.dpls.OrderExecuted;
import com.longexposure.dpls.OrderModify;
import com.longexposure.tops.QuoteUpdate;
import com.longexposure.wire.IexMessage;
import com.longexposure.wire.ProtectedBbo;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Map;

/**
 * Single-symbol, narrow-time-window trace of merged DPLS and TOPS events
 * plus the derived order-book state after each DPLS event. Used to
 * inspect specific BBO-mismatch cases that the {@link DplsBboCrossValidator}
 * surfaces.
 *
 * <p>The merge ordering matches the validator's: DPLS wins timestamp ties.
 * That's the algorithm under investigation — if its assumption is wrong,
 * we'll see it here.
 *
 * <p>Window is centered on a target timestamp and expressed in milliseconds.
 * Filter is on symbol equality. Both narrow enough that output is human-
 * readable; opening any whole trading day for a high-volume symbol would
 * produce too much.
 */
public final class EventTracer {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSSSSS").withZone(ZoneOffset.UTC);

    private final String targetSymbol;
    private final long windowStartNanos;
    private final long windowEndNanos;
    private final long targetTsNanos;
    private final OrderBookManager bookManager = new OrderBookManager();
    private final PrintStream out;
    private int linesEmitted;

    public EventTracer(final String targetSymbol, final long targetTsNanos, final long windowMs, final PrintStream out) {
        this.targetSymbol = targetSymbol;
        this.targetTsNanos = targetTsNanos;
        long windowNanos = windowMs * 1_000_000L;
        this.windowStartNanos = targetTsNanos - windowNanos;
        this.windowEndNanos = targetTsNanos + windowNanos;
        this.out = out;
    }

    public void run(final Path dplsFile, final Path topsFile) throws IOException {
        out.printf("== trace: symbol=%s  target=%s  window=±%dms ==%n",
                targetSymbol, formatNanos(targetTsNanos),
                (windowEndNanos - windowStartNanos) / 2 / 1_000_000L);
        out.printf("window: %s  →  %s%n",
                formatNanos(windowStartNanos), formatNanos(windowEndNanos));
        out.println();

        try (MessageStream deep = MessageStream.dpls(dplsFile);
             MessageStream tops = MessageStream.tops(topsFile)) {

            while (!deep.isExhausted() || !tops.isExhausted()) {
                long deepTs = deep.peekTs();
                long topsTs = tops.peekTs();

                // Optimization: skip the bulk of the file before the window
                if (deepTs < windowStartNanos && topsTs < windowStartNanos) {
                    if (deepTs <= topsTs) {
                        // Still apply DPLS to the book so by the time we hit the
                        // window the book state is correct for this symbol. Only
                        // apply to the symbol of interest; that's a heavy filter.
                        IexMessage m = deep.consume();
                        applyToBook(m);
                    } else {
                        tops.consume();
                    }
                    continue;
                }

                // Past the window? we're done.
                if (deepTs > windowEndNanos && topsTs > windowEndNanos) {
                    break;
                }

                if (deepTs <= topsTs) {
                    IexMessage m = deep.consume();
                    applyToBook(m);
                    if (deepTs >= windowStartNanos && deepTs <= windowEndNanos && symbolOf(m).equals(targetSymbol)) {
                        printDeepEvent(deepTs, m);
                    }
                } else {
                    IexMessage m = tops.consume();
                    if (topsTs >= windowStartNanos && topsTs <= windowEndNanos
                            && m instanceof QuoteUpdate qu && qu.symbol().equals(targetSymbol)) {
                        printTopsQuote(topsTs, qu);
                    }
                }
            }
        }

        out.println();
        out.println("== trace complete (" + linesEmitted + " lines emitted) ==");
    }

    private void applyToBook(final IexMessage m) {
        bookManager.apply(m);
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

    private void printDeepEvent(final long ts, final IexMessage m) {
        OrderBook book = bookManager.book(targetSymbol);
        String description = describe(m);
        out.printf("[%s] DPLS  %s%n", formatNanos(ts), description);
        printBookState(book, "    ");
        linesEmitted++;
    }

    private void printTopsQuote(final long ts, final QuoteUpdate qu) {
        OrderBook book = bookManager.book(targetSymbol);
        ProtectedBbo bid = book == null ? ProtectedBbo.EMPTY : book.bestBidProtected();
        ProtectedBbo ask = book == null ? ProtectedBbo.EMPTY : book.bestAskProtected();
        long derivedBidPrice = bid.priceRaw();
        long derivedBidSize  = bid.size();
        long derivedAskPrice = ask.priceRaw();
        long derivedAskSize  = ask.size();

        boolean bidMatch = derivedBidPrice == qu.bidPriceRaw() && derivedBidSize == (long) qu.bidSize();
        boolean askMatch = derivedAskPrice == qu.askPriceRaw() && derivedAskSize == (long) qu.askSize();
        String mark = (bidMatch && askMatch) ? "MATCH " : "MISMATCH";

        out.printf("[%s] TOPS QuoteUpdate  flags=0x%02x  %s%n",
                formatNanos(ts), qu.flags() & 0xff, mark);
        out.printf("    TOPS    bid: %d @ %d ($%.4f)   ask: %d @ %d ($%.4f)%n",
                qu.bidSize(), qu.bidPriceRaw(), qu.bidPrice(),
                qu.askSize(), qu.askPriceRaw(), qu.askPrice());
        out.printf("    derived bid: %d @ %d ($%.4f)   ask: %d @ %d ($%.4f)%n",
                derivedBidSize, derivedBidPrice, derivedBidPrice / 10_000.0,
                derivedAskSize, derivedAskPrice, derivedAskPrice / 10_000.0);
        printBookState(book, "      ");
        linesEmitted++;
    }

    private static String describe(final IexMessage m) {
        return switch (m) {
            case AddOrder a -> String.format("AddOrder      %s  side=%s  id=%d  %d @ %d ($%.4f)",
                    a.symbol(), a.side(), a.orderId(), a.size(), a.priceRaw(), a.price());
            case OrderModify om -> String.format("OrderModify   %s  id=%d  newSize=%d  newPrice=%d ($%.4f)  prio=%s",
                    om.symbol(), om.orderId(), om.size(), om.priceRaw(), om.price(),
                    om.maintainPriority() ? "maintain" : "reset");
            case OrderDelete od -> String.format("OrderDelete   %s  id=%d", od.symbol(), od.orderId());
            case OrderExecuted oe -> String.format("OrderExecuted %s  id=%d  %d @ %d ($%.4f)  tradeId=%d",
                    oe.symbol(), oe.orderId(), oe.size(), oe.priceRaw(), oe.price(), oe.tradeId());
            case ClearBook cb -> String.format("ClearBook     %s", cb.symbol());
            default -> m.getClass().getSimpleName();
        };
    }

    private void printBookState(final OrderBook book, final String indent) {
        if (book == null || book.isEmpty()) {
            out.println(indent + "book: <empty>");
            return;
        }
        out.printf("%sbook: %d orders / %d bid levels / %d ask levels%n",
                indent, book.orderCount(), book.bidLevelCount(), book.askLevelCount());

        out.print(indent + "  bid: ");
        printLevels(book.bidLevels(), 5);
        out.println();

        out.print(indent + "  ask: ");
        printLevels(book.askLevels(), 5);
        out.println();
    }

    private void printLevels(final Map<Long, Long> levels, final int max) {
        int i = 0;
        Iterator<Map.Entry<Long, Long>> it = levels.entrySet().iterator();
        while (it.hasNext() && i < max) {
            Map.Entry<Long, Long> e = it.next();
            if (i > 0) out.print("  ");
            out.printf("%d @ %d", e.getValue(), e.getKey());
            i++;
        }
        if (it.hasNext()) out.print("  ...");
    }

    private static String formatNanos(final long nanosSinceEpoch) {
        long seconds = nanosSinceEpoch / 1_000_000_000L;
        long nanos = nanosSinceEpoch % 1_000_000_000L;
        if (nanos < 0) {
            nanos += 1_000_000_000L;
            seconds -= 1;
        }
        return TS_FMT.format(Instant.ofEpochSecond(seconds, nanos));
    }
}
