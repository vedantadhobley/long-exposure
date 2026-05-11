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
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-validates a DPLS HIST file against a TOPS HIST file from the
 * same trading day by reconstructing the order-level book from DPLS
 * events and comparing the derived round-lot-protected top-of-book
 * against every TOPS Quote Update. See {@link DeepBboCrossValidator}
 * for the price-level twin.
 *
 * <p>Round-lot protection ({@link ProtectedBbo}) is required to match
 * TOPS semantics: TOPS only publishes a price level as the BBO when
 * aggregate displayed quantity at that level meets the round-lot
 * threshold, mirroring Regulation NMS protected-quote rules. An
 * unprotected derivation produces false mismatches every time the IEX
 * book has an odd-lot order resting above the round-lot top — which
 * happens often in practice.
 *
 * <p>Algorithm — single pass, merge-by-timestamp:
 * <ol>
 *   <li>Open both files as {@link MessageStream}s (each with a 1s
 *       reorder buffer; engine timestamps are not globally monotonic).
 *   <li>Peek both. Apply the message with the earlier timestamp (DPLS
 *       wins ties — TOPS QuoteUpdates report post-matching-engine state).
 *   <li>DPLS messages feed the {@link OrderBookManager} (book-affecting
 *       ones: AddOrder / OrderModify / OrderDelete / OrderExecuted /
 *       ClearBook). Non-book messages are ignored at this layer.
 *   <li>TOPS QuoteUpdates trigger a comparison: derived BBO from the
 *       book vs the QuoteUpdate's bid/ask price + size. Other TOPS
 *       message types are ignored.
 *   <li>Per-symbol stats accumulated. Top-N mismatches reported at end.
 * </ol>
 *
 * <p>Within a single nanosecond timestamp the matching engine can emit
 * multiple events (e.g. one aggressive order hitting N resting orders).
 * Applying DPLS first when ties on ts means we always compare TOPS's
 * post-event state against our post-event book state — TOPS publishes
 * QuoteUpdates only after the matching engine has resolved the event.
 */
public final class DplsBboCrossValidator {

    private final OrderBookManager bookManager = new OrderBookManager();
    private final Map<String, Long> roundLotBySymbol;

    public DplsBboCrossValidator() {
        this(Map.of());
    }

    /**
     * @param roundLotBySymbol per-symbol round-lot (derived from
     *                         prior-day close). Symbols not in the
     *                         map fall back to level-price tier.
     */
    public DplsBboCrossValidator(final Map<String, Long> roundLotBySymbol) {
        this.roundLotBySymbol = roundLotBySymbol;
    }

    private long totalQuotesCompared;
    private long matched;
    private long mismatched;
    private long depthEventsApplied;
    private long topsNonQuoteEventsSkipped;

    private final Map<String, BboValidationResult.SymbolStats> perSymbol = new HashMap<>();
    private final List<BboValidationResult.MismatchSample> mismatchSamples = new ArrayList<>();
    private static final int MAX_SAMPLES = 20;

    /**
     * Pending TOPS QuoteUpdates awaiting comparison, keyed by symbol.
     * Multiple QuoteUpdates at the same {@code (symbol, ts)} get
     * squashed here — only the latest survives, representing the BBO
     * after the final of multiple same-ns atomic transactions.
     *
     * <p>Comparison is deferred until either (a) we're about to apply
     * a DPLS book event for the same symbol — at which point the
     * symbol's book is still in its "at pending.ts" state since no
     * events for it have arrived since, or (b) end of stream. This
     * gives O(1) flush per event instead of the O(symbols) iteration
     * a periodic-flush design would do.
     */
    private final Map<String, QuoteUpdate> pendingBySymbol = new HashMap<>();

    public BboValidationResult run(final Path dplsFile, final Path topsFile) throws IOException {
        long startNanos = System.nanoTime();

        try (MessageStream deep = MessageStream.dpls(dplsFile);
             MessageStream tops = MessageStream.tops(topsFile)) {

            while (!deep.isExhausted() || !tops.isExhausted()) {
                long deepTs = deep.peekTs();
                long topsTs = tops.peekTs();

                if (deepTs <= topsTs) {
                    IexMessage m = deep.consume();
                    String sym = bookAffectingSymbol(m);
                    if (sym != null) {
                        preApplyFlush(sym, deepTs);
                    }
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
            // End-of-stream: flush every remaining pending. Each one's
            // symbol had no further book events after the pending ts, so
            // the book is still at the correct "at pending.ts" state.
            for (QuoteUpdate qu : pendingBySymbol.values()) {
                compareBbo(qu);
            }
            pendingBySymbol.clear();

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

    /**
     * Buffer a QuoteUpdate by symbol. Same-ns replacements at the same
     * symbol are silently overwritten (final QU at the ns wins). An
     * older-ts pending for the same symbol gets flushed first (this
     * can happen when TOPS publishes consecutive QUs for the symbol
     * without a DPLS event in between — the older one represents an
     * older engine state we'd never compare against otherwise).
     */
    private void stashPendingQu(final QuoteUpdate qu) {
        QuoteUpdate prev = pendingBySymbol.get(qu.symbol());
        if (prev != null && prev.timestampNanos() < qu.timestampNanos()) {
            compareBbo(prev);
        }
        pendingBySymbol.put(qu.symbol(), qu);
    }

    /**
     * Before applying a book-affecting event for {@code symbol} at
     * {@code newTs}, flush any pending QU for the same symbol at a
     * strictly earlier ts. Symbol's book is still in its "at pending.ts"
     * state at this point, since no events for this symbol have
     * arrived since pending.ts. Same-ns matches are NOT flushed — DPLS
     * wins ties so the new DPLS event represents a state at or before
     * the pending QU's reporting instant; the pending stays valid until
     * a strictly later event.
     */
    private void preApplyFlush(final String symbol, final long newTs) {
        QuoteUpdate pending = pendingBySymbol.get(symbol);
        if (pending != null && pending.timestampNanos() < newTs) {
            compareBbo(pending);
            pendingBySymbol.remove(symbol);
        }
    }

    /** Symbol affected by the message, or {@code null} for non-book-affecting types. */
    private static String bookAffectingSymbol(final IexMessage m) {
        return switch (m) {
            case AddOrder a       -> a.symbol();
            case OrderModify om   -> om.symbol();
            case OrderDelete od   -> od.symbol();
            case OrderExecuted oe -> oe.symbol();
            case ClearBook cb     -> cb.symbol();
            default -> null;
        };
    }

    private void compareBbo(final QuoteUpdate qu) {
        totalQuotesCompared++;
        BboValidationResult.SymbolStats stats = perSymbol.computeIfAbsent(
                qu.symbol(), BboValidationResult.SymbolStats::new);

        OrderBook book = bookManager.book(qu.symbol());
        long rl = roundLotBySymbol.getOrDefault(qu.symbol(), 0L);
        ProtectedBbo bid = book == null ? ProtectedBbo.EMPTY : book.bestBidProtected(rl);
        ProtectedBbo ask = book == null ? ProtectedBbo.EMPTY : book.bestAskProtected(rl);

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
