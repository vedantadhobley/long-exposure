package com.longexposure.wire;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-symbol prior-day close prices, derived once (e.g. by
 * {@code PriorCloseExtractor} parsing yesterday's TOPS file) and
 * loaded as a CSV at validator startup.
 *
 * <p>The map's value is the symbol's close in IEX raw 4-decimal price
 * encoding, suitable for direct lookup into {@link RoundLot#forPriceRaw}.
 *
 * <p>Designed as a one-shot file load — the data is immutable per
 * trading day, and we don't model multi-day caches here.
 */
public final class PriorClose {

    private PriorClose() {}

    /**
     * Load a {@code symbol,price_raw} CSV. Skips the header line.
     * Returns a {@link Map} keyed by symbol with raw-price values.
     */
    public static Map<String, Long> loadCsv(final Path csvFile) throws IOException {
        Map<String, Long> out = new HashMap<>(16_384);
        try (BufferedReader r = Files.newBufferedReader(csvFile)) {
            String line = r.readLine();          // header
            if (line == null) return out;
            while ((line = r.readLine()) != null) {
                int comma = line.indexOf(',');
                if (comma <= 0 || comma == line.length() - 1) continue;
                String symbol = line.substring(0, comma);
                long priceRaw = Long.parseLong(line.substring(comma + 1));
                out.put(symbol, priceRaw);
            }
        }
        return out;
    }

    /**
     * Reduce a prior-close map to per-symbol round-lot under the
     * Reg-NMS tier rule. The result is what
     * {@code BboCrossValidator}s feed to {@link ProtectedBbo} as the
     * symbol's fixed round-lot for the trading day.
     */
    public static Map<String, Long> roundLotBySymbol(final Map<String, Long> priorCloseRaw) {
        Map<String, Long> out = new HashMap<>(priorCloseRaw.size());
        for (Map.Entry<String, Long> e : priorCloseRaw.entrySet()) {
            out.put(e.getKey(), RoundLot.forPriceRaw(e.getValue()));
        }
        return out;
    }
}
