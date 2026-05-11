package com.longexposure.validation;

import com.longexposure.deep.PriceLevelUpdate;
import com.longexposure.dpls.AddOrder;
import com.longexposure.dpls.OrderBook;
import com.longexposure.dpls.OrderBookManager;
import com.longexposure.wire.IexMessage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The third leg of the triangle. Streams the DPLS HIST file and the DEEP
 * HIST file for the same trading day, merges by timestamp, and after
 * every DEEP price-level transaction completes, compares the DEEP-stated
 * aggregate at that {@code (symbol, side, price)} to what DPLS's
 * order-level book has aggregated at the same level.
 *
 * <p>Information-theoretically, DEEP is exactly what you get by summing
 * DPLS orders by {@code (symbol, side, price)} and emitting any change
 * to that sum as a Price Level Update. So at every DEEP transaction
 * boundary the two views <strong>must</strong> agree exactly. Any
 * disagreement points to a parser bug, a missed event, or a
 * spec-semantics gap — and the disagreement is independent of TOPS,
 * making this a useful third oracle.
 *
 * <p><b>Why only on transaction-complete PLUs</b>: a single matching-
 * engine event can change multiple price levels atomically. DEEP
 * serializes that as a run of PLUs with event flag {@code 0x00}
 * followed by a final flag {@code 0x01} PLU. The intermediate states
 * are deliberately transient — DPLS may apply its events at slightly
 * different (engine-timestamp-identical) points within that run, so
 * comparing mid-transaction yields false mismatches. We compare only
 * once a DEEP transaction is closed.
 *
 * <p><b>Merge rule</b>: DPLS wins on timestamp ties so its order-level
 * events apply before we read DEEP's transaction-complete PLU at the
 * same nanosecond. Mirrors the {@link DplsBboCrossValidator} merge
 * semantics.
 */
public final class DeepVsDplsValidator {

    private final OrderBookManager dplsBookManager = new OrderBookManager();

    private long dplsEventsApplied;
    private long dplsBecauseTie;            // diagnostic — events processed on a DPLS → DEEP tie
    private long deepEventsObserved;
    private long deepTransactionsCompared;      // only flag=0x01 PLUs
    private long deepMidTransactionSkipped;     // flag=0x00 PLUs (still applied to DEEP book if we kept one)
    private long matched;
    private long mismatched;

    private final Map<String, BboValidationResult.SymbolStats> perSymbol = new HashMap<>();
    private final List<LevelMismatch> mismatchSamples = new ArrayList<>();
    private static final int MAX_SAMPLES = 20;

    public Result run(final Path dplsFile, final Path deepFile) throws IOException {
        long startNanos = System.nanoTime();

        try (MessageStream dp = MessageStream.dpls(dplsFile);
             MessageStream dd = MessageStream.deep(deepFile)) {

            while (!dp.isExhausted() || !dd.isExhausted()) {
                long dpTs = dp.peekTs();
                long ddTs = dd.peekTs();
                if (dpTs <= ddTs) {
                    IexMessage m = dp.consume();
                    dplsBookManager.apply(m);
                    dplsEventsApplied++;
                    if (dpTs == ddTs) dplsBecauseTie++;
                } else {
                    IexMessage m = dd.consume();
                    deepEventsObserved++;
                    if (m instanceof PriceLevelUpdate plu) {
                        if (plu.isTransactionComplete()) {
                            compareLevel(plu);
                        } else {
                            deepMidTransactionSkipped++;
                        }
                    }
                }
            }

            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            return new Result(
                    Duration.ofMillis(elapsedMs),
                    dplsEventsApplied,
                    dplsBecauseTie,
                    deepEventsObserved,
                    deepTransactionsCompared,
                    deepMidTransactionSkipped,
                    matched,
                    mismatched,
                    dplsBookManager.symbolCount(),
                    Map.copyOf(perSymbol),
                    List.copyOf(mismatchSamples),
                    dp.heartbeatPacketsSkipped() + dd.heartbeatPacketsSkipped(),
                    dp.decodeFailures() + dd.decodeFailures());
        }
    }

    private void compareLevel(final PriceLevelUpdate plu) {
        deepTransactionsCompared++;
        BboValidationResult.SymbolStats stats = perSymbol.computeIfAbsent(
                plu.symbol(), BboValidationResult.SymbolStats::new);

        OrderBook dpBook = dplsBookManager.book(plu.symbol());
        AddOrder.Side dpSide = (plu.side() == PriceLevelUpdate.Side.BUY)
                ? AddOrder.Side.BUY : AddOrder.Side.SELL;
        long dpAggregate = dpBook == null ? 0L : dpBook.aggregateAt(dpSide, plu.priceRaw());
        long deepSize = plu.size();

        if (dpAggregate == deepSize) {
            matched++;
            stats.matched++;
        } else {
            mismatched++;
            stats.mismatched++;
            if (mismatchSamples.size() < MAX_SAMPLES) {
                mismatchSamples.add(new LevelMismatch(
                        plu.symbol(),
                        plu.timestampNanos(),
                        plu.side(),
                        plu.priceRaw(),
                        dpAggregate,
                        deepSize));
            }
        }
    }

    public record Result(
            Duration elapsed,
            long dplsEventsApplied,
            long dplsEventsOnTie,
            long deepEventsObserved,
            long deepTransactionsCompared,
            long deepMidTransactionSkipped,
            long matched,
            long mismatched,
            int symbolsTracked,
            Map<String, BboValidationResult.SymbolStats> perSymbol,
            List<LevelMismatch> mismatchSamples,
            long heartbeatsSkipped,
            long decodeFailures) {

        public double matchRate() {
            return deepTransactionsCompared == 0 ? 0.0 : (double) matched / deepTransactionsCompared;
        }

        public List<BboValidationResult.SymbolStats> worstSymbols(final int n) {
            return perSymbol.values().stream()
                    .filter(s -> s.mismatched() > 0)
                    .sorted(Comparator.comparingLong(BboValidationResult.SymbolStats::mismatched).reversed())
                    .limit(n)
                    .toList();
        }
    }

    public record LevelMismatch(
            String symbol,
            long timestampNanos,
            PriceLevelUpdate.Side side,
            long priceRaw,
            long dplsAggregate,
            long deepSize) {
    }
}
