package com.longexposure.validation;

import com.longexposure.deepplus.OrderBook;
import com.longexposure.deepplus.OrderBookManager;
import com.longexposure.tops.QuoteUpdate;
import com.longexposure.wire.IexMessage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Cross-validates a DEEP+ HIST file against a TOPS HIST file from the
 * same trading day by reconstructing the order book from DEEP+ events
 * and comparing the derived top-of-book against every TOPS Quote Update.
 *
 * <p>Algorithm — single pass, merge-by-timestamp:
 * <ol>
 *   <li>Open both files as {@link MessageStream}s.
 *   <li>Peek both. Apply the message with the earlier timestamp (DEEP+
 *       wins ties — TOPS QuoteUpdates report post-matching-engine state).
 *   <li>DEEP+ messages feed the {@link OrderBookManager} (book-affecting
 *       ones: AddOrder / OrderModify / OrderDelete / OrderExecuted /
 *       ClearBook). Non-book messages are ignored at this layer.
 *   <li>TOPS QuoteUpdates trigger a comparison: derived BBO from the
 *       book vs the QuoteUpdate's bid/ask price + size. Other TOPS
 *       message types are ignored.
 *   <li>Per-symbol stats accumulated. Top-10 mismatches by frequency
 *       reported at end.
 * </ol>
 *
 * <p>Within a single nanosecond timestamp the matching engine can emit
 * multiple events (e.g., one aggressive order hitting N resting orders).
 * Applying DEEP+ first when ties on ts means we always compare TOPS's
 * post-event state against our post-event book state, which is the
 * correct semantics — TOPS publishes QuoteUpdates only after the matching
 * engine has resolved the event.
 */
public final class BboCrossValidator {

    private final OrderBookManager bookManager = new OrderBookManager();

    private long totalQuotesCompared;
    private long matched;
    private long mismatched;
    private long deepEventsApplied;
    private long topsNonQuoteEventsSkipped;

    private final Map<String, SymbolStats> perSymbol = new HashMap<>();
    private final List<MismatchSample> mismatchSamples = new ArrayList<>();
    private static final int MAX_SAMPLES = 20;

    public Result run(final Path deepPlusFile, final Path topsFile) throws IOException {
        long startNanos = System.nanoTime();

        try (MessageStream deep = MessageStream.deepPlus(deepPlusFile);
             MessageStream tops = MessageStream.tops(topsFile)) {

            while (!deep.isExhausted() || !tops.isExhausted()) {
                long deepTs = deep.peekTs();
                long topsTs = tops.peekTs();
                if (deepTs <= topsTs) {
                    IexMessage m = deep.consume();
                    bookManager.apply(m);
                    deepEventsApplied++;
                } else {
                    IexMessage m = tops.consume();
                    if (m instanceof QuoteUpdate qu) {
                        compareBbo(qu);
                    } else {
                        topsNonQuoteEventsSkipped++;
                    }
                }
            }

            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            return new Result(
                    Duration.ofMillis(elapsedMs),
                    deepEventsApplied,
                    topsNonQuoteEventsSkipped,
                    totalQuotesCompared,
                    matched,
                    mismatched,
                    bookManager.symbolCount(),
                    Map.copyOf(perSymbol),
                    List.copyOf(mismatchSamples),
                    deep.heartbeatPacketsSkipped() + tops.heartbeatPacketsSkipped(),
                    deep.decodeFailures() + tops.decodeFailures());
        }
    }

    private void compareBbo(final QuoteUpdate qu) {
        totalQuotesCompared++;
        SymbolStats stats = perSymbol.computeIfAbsent(qu.symbol(), SymbolStats::new);

        OrderBook book = bookManager.book(qu.symbol());

        long derivedBidPriceRaw = 0L;
        long derivedAskPriceRaw = 0L;
        long derivedBidSize = 0L;
        long derivedAskSize = 0L;
        if (book != null) {
            OptionalLong bid = book.bestBidPriceRaw();
            if (bid.isPresent()) {
                derivedBidPriceRaw = bid.getAsLong();
                derivedBidSize = book.sizeAtBestBid();
            }
            OptionalLong ask = book.bestAskPriceRaw();
            if (ask.isPresent()) {
                derivedAskPriceRaw = ask.getAsLong();
                derivedAskSize = book.sizeAtBestAsk();
            }
        }

        boolean bidMatch = derivedBidPriceRaw == qu.bidPriceRaw() && derivedBidSize == (long) qu.bidSize();
        boolean askMatch = derivedAskPriceRaw == qu.askPriceRaw() && derivedAskSize == (long) qu.askSize();

        if (bidMatch && askMatch) {
            matched++;
            stats.matched++;
        } else {
            mismatched++;
            stats.mismatched++;
            if (mismatchSamples.size() < MAX_SAMPLES) {
                mismatchSamples.add(new MismatchSample(
                        qu.symbol(),
                        qu.timestampNanos(),
                        derivedBidPriceRaw, derivedBidSize,
                        derivedAskPriceRaw, derivedAskSize,
                        qu.bidPriceRaw(), (long) qu.bidSize(),
                        qu.askPriceRaw(), (long) qu.askSize()));
            }
        }
    }

    public record Result(
            Duration elapsed,
            long deepEventsApplied,
            long topsNonQuoteEventsSkipped,
            long totalQuotesCompared,
            long matched,
            long mismatched,
            int symbolsTracked,
            Map<String, SymbolStats> perSymbol,
            List<MismatchSample> mismatchSamples,
            long heartbeatsSkipped,
            long decodeFailures) {

        public double matchRate() {
            return totalQuotesCompared == 0 ? 0.0 : (double) matched / totalQuotesCompared;
        }

        public List<SymbolStats> worstSymbols(final int n) {
            return perSymbol.values().stream()
                    .filter(s -> s.mismatched > 0)
                    .sorted(Comparator.comparingLong(SymbolStats::mismatched).reversed())
                    .limit(n)
                    .toList();
        }

        public List<SymbolStats> perfectSymbolsCount() {
            return perSymbol.values().stream()
                    .filter(s -> s.mismatched == 0 && s.matched > 0)
                    .toList();
        }
    }

    public static final class SymbolStats {
        private final String symbol;
        long matched;
        long mismatched;

        SymbolStats(final String symbol) {
            this.symbol = symbol;
        }

        public String symbol() { return symbol; }
        public long matched() { return matched; }
        public long mismatched() { return mismatched; }
        public long total() { return matched + mismatched; }

        public double matchRate() {
            return total() == 0 ? 0.0 : (double) matched / total();
        }
    }

    public record MismatchSample(
            String symbol,
            long timestampNanos,
            long derivedBidPriceRaw, long derivedBidSize,
            long derivedAskPriceRaw, long derivedAskSize,
            long topsBidPriceRaw,    long topsBidSize,
            long topsAskPriceRaw,    long topsAskSize) {
    }
}
