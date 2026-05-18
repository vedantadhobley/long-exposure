package com.longexposure.scoring;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Helpers that scorers call to mutate a breakdown {@link ObjectNode}
 * with per-symbol metadata (company name, listing exchange, ETF flag,
 * round lot, previous close) at the moment the breakdown is built.
 *
 * <p>The metadata comes from {@link ScoringContext#lookupSymbol(String)},
 * which reads from the in-memory map loaded once per scoring run. So
 * every call here is O(1) and incurs zero DB round-trips.
 *
 * <p>Missing-field-safe: if the symbol isn't in the reference table
 * (obscure ticker, newly listed since last weekly refresh), the
 * breakdown is left untouched. The LLM falls back to narrating with
 * just the ticker, which is what it does today.
 *
 * <p>Field names mirror the {@code symbols} table columns so the LLM
 * has consistent naming across all scorer types.
 */
public final class Enrich {

    private Enrich() {}

    /**
     * Add per-symbol fields to a breakdown that's about to be persisted
     * to {@code scored_events.breakdown}. Mutates {@code breakdown} in
     * place. Null-safe on all branches.
     */
    public static void symbol(final ObjectNode breakdown, final ScoringContext ctx, final String symbol) {
        if (breakdown == null || ctx == null || symbol == null) return;
        SymbolMetadata m = ctx.lookupSymbol(symbol);
        if (m == null) return;

        if (m.companyName() != null)       breakdown.put("company_name",       m.companyName());
        if (m.listingExchange() != null)   breakdown.put("listing_exchange",   m.listingExchange());
        if (m.isEtf() != null)             breakdown.put("is_etf",             m.isEtf());
        if (m.roundLot() != null)          breakdown.put("round_lot",          m.roundLot());
        if (m.prevCloseDollars() != null)  breakdown.put("prev_close_dollars", m.prevCloseDollars());
        if (m.luldTier() != null)          breakdown.put("luld_tier",          m.luldTier());
    }
}
