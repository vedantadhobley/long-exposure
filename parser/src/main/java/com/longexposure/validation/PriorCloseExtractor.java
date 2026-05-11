package com.longexposure.validation;

import com.longexposure.tops.TradeReport;
import com.longexposure.wire.IexMessage;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stream a TOPS HIST .pcap.gz from one trading day and emit each
 * symbol's <strong>last observed</strong> {@link TradeReport} price.
 *
 * <p>That last trade price is — by definition — the closing price IEX
 * recorded for the symbol that day. Used as input to per-symbol
 * round-lot tier determination (Reg-NMS Market Data Infrastructure
 * rule: round-lot tier is fixed for the trading day based on the
 * prior day's close).
 *
 * <p>Output format: CSV with header {@code symbol,price_raw}, one row
 * per traded symbol, sorted by symbol. Price is in IEX's raw 4-decimal
 * encoding (e.g. {@code 990500} = $99.05).
 *
 * <p>Symbols that traded on IEX yesterday but not today (or vice
 * versa) are naturally covered — the extractor only looks at what's
 * in the input file; the loader applies a level-price-tier fallback
 * for any symbol not in the cache.
 */
public final class PriorCloseExtractor {

    private PriorCloseExtractor() {}

    public static int extract(final Path topsFile, final Path outputCsv) throws IOException {
        Map<String, Long> lastTradePrice = new HashMap<>();

        try (MessageStream tops = MessageStream.tops(topsFile)) {
            while (!tops.isExhausted()) {
                IexMessage m = tops.consume();
                if (m instanceof TradeReport tr) {
                    lastTradePrice.put(tr.symbol(), tr.priceRaw());
                }
            }
        }

        try (BufferedWriter w = Files.newBufferedWriter(outputCsv)) {
            w.write("symbol,price_raw\n");
            for (Map.Entry<String, Long> e : new TreeMap<>(lastTradePrice).entrySet()) {
                w.write(e.getKey());
                w.write(',');
                w.write(Long.toString(e.getValue()));
                w.write('\n');
            }
        }

        return lastTradePrice.size();
    }
}
