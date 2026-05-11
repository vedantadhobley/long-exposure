package com.longexposure.validation;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Aggregate result of a depth-feed → TOPS BBO cross-validation run.
 *
 * <p>Shape is feed-agnostic — used by both {@link DplsBboCrossValidator}
 * (DPLS order-level book) and {@link DeepBboCrossValidator} (DEEP
 * price-level book). The {@code depthEventsApplied} count refers to
 * whichever depth feed produced the input.
 */
public record BboValidationResult(
        Duration elapsed,
        long depthEventsApplied,
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

    public static final class SymbolStats {
        private final String symbol;
        long matched;
        long mismatched;

        SymbolStats(final String symbol) {
            this.symbol = symbol;
        }

        public String symbol()    { return symbol; }
        public long matched()     { return matched; }
        public long mismatched()  { return mismatched; }
        public long total()       { return matched + mismatched; }

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
