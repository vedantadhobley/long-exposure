package com.longexposure.validation;

import com.longexposure.deep.PriceLevelBook;
import com.longexposure.deep.PriceLevelBookManager;
import com.longexposure.tops.QuoteUpdate;
import com.longexposure.wire.IexMessage;
import com.longexposure.wire.ProtectedBbo;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The DEEP twin of {@link DplsBboCrossValidator}: streams a DEEP
 * HIST file against the TOPS HIST file for the same trading day,
 * derives the round-lot-protected best bid/ask from the price-level
 * book, and compares to every {@link QuoteUpdate}.
 *
 * <p>DEEP carries <em>aggregate displayed size at every price level</em>,
 * which is information-theoretically sufficient to reconstruct TOPS BBO
 * (TOPS is the round-lot-protected top of DEEP's book). 100% agreement
 * is the expected outcome — any disagreement points to a parser bug, a
 * spec-semantics gap, or a quote-source path that bypasses the DEEP
 * stream (e.g. specialist stub quotes).
 *
 * <p>Algorithm identical to {@link DplsBboCrossValidator} except the
 * book manager is {@link PriceLevelBookManager} (price-level), not
 * {@link com.longexposure.dpls.OrderBookManager} (order-level). The
 * merge-by-timestamp + reorder buffer logic is shared via
 * {@link MessageStream}.
 */
public final class DeepBboCrossValidator {

    private final PriceLevelBookManager bookManager = new PriceLevelBookManager();

    private long totalQuotesCompared;
    private long matched;
    private long mismatched;
    private long depthEventsApplied;
    private long topsNonQuoteEventsSkipped;

    private final Map<String, BboValidationResult.SymbolStats> perSymbol = new HashMap<>();
    private final List<BboValidationResult.MismatchSample> mismatchSamples = new ArrayList<>();
    private static final int MAX_SAMPLES = 20;

    /** Same-ns dedupe — see {@link DplsBboCrossValidator#pendingBySymbol} for rationale. */
    private final Map<String, QuoteUpdate> pendingBySymbol = new HashMap<>();
    private long lastFlushedTs = Long.MIN_VALUE;

    public BboValidationResult run(final Path deepFile, final Path topsFile) throws IOException {
        long startNanos = System.nanoTime();

        try (MessageStream deep = MessageStream.deep(deepFile);
             MessageStream tops = MessageStream.tops(topsFile)) {

            while (!deep.isExhausted() || !tops.isExhausted()) {
                long deepTs = deep.peekTs();
                long topsTs = tops.peekTs();
                long nextTs = Math.min(deepTs, topsTs);
                if (nextTs > lastFlushedTs) {
                    flushPendingOlderThan(nextTs);
                    lastFlushedTs = nextTs;
                }

                if (deepTs <= topsTs) {
                    IexMessage m = deep.consume();
                    bookManager.apply(m);
                    depthEventsApplied++;
                } else {
                    IexMessage m = tops.consume();
                    if (m instanceof QuoteUpdate qu) {
                        stashPendingQu(qu);
                    } else {
                        topsNonQuoteEventsSkipped++;
                    }
                }
            }
            flushPendingOlderThan(Long.MAX_VALUE);

            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            return new BboValidationResult(
                    Duration.ofMillis(elapsedMs),
                    depthEventsApplied,
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

    private void stashPendingQu(final QuoteUpdate qu) {
        QuoteUpdate prev = pendingBySymbol.get(qu.symbol());
        if (prev != null && prev.timestampNanos() < qu.timestampNanos()) {
            compareBbo(prev);
        }
        pendingBySymbol.put(qu.symbol(), qu);
    }

    private void flushPendingOlderThan(final long ts) {
        if (pendingBySymbol.isEmpty()) return;
        var it = pendingBySymbol.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getValue().timestampNanos() < ts) {
                compareBbo(e.getValue());
                it.remove();
            }
        }
    }

    private void compareBbo(final QuoteUpdate qu) {
        totalQuotesCompared++;
        BboValidationResult.SymbolStats stats = perSymbol.computeIfAbsent(
                qu.symbol(), BboValidationResult.SymbolStats::new);

        PriceLevelBook book = bookManager.book(qu.symbol());
        ProtectedBbo bid = book == null ? ProtectedBbo.EMPTY : book.bestBidProtected();
        ProtectedBbo ask = book == null ? ProtectedBbo.EMPTY : book.bestAskProtected();

        boolean bidMatch = bid.priceRaw() == qu.bidPriceRaw() && bid.size() == (long) qu.bidSize();
        boolean askMatch = ask.priceRaw() == qu.askPriceRaw() && ask.size() == (long) qu.askSize();

        if (bidMatch && askMatch) {
            matched++;
            stats.matched++;
        } else {
            mismatched++;
            stats.mismatched++;
            if (mismatchSamples.size() < MAX_SAMPLES) {
                mismatchSamples.add(new BboValidationResult.MismatchSample(
                        qu.symbol(),
                        qu.timestampNanos(),
                        bid.priceRaw(), bid.size(),
                        ask.priceRaw(), ask.size(),
                        qu.bidPriceRaw(), (long) qu.bidSize(),
                        qu.askPriceRaw(), (long) qu.askSize()));
            }
        }
    }
}
