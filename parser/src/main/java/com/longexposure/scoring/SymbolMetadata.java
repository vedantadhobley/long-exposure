package com.longexposure.scoring;

/**
 * Per-ticker metadata that gets joined into the breakdown JSON of every
 * scored event. Read once from the {@code symbols} reference table at
 * the start of each {@code ScoreEventsActivity} run and cached in-memory
 * for the duration. See {@code docs/concepts.md} §10 (B) for the design
 * rationale.
 *
 * <p>All fields are nullable — we may not have data for every symbol
 * (especially obscure tickers or symbols newly listed since the last
 * weekly refresh). Scorers should defensively skip missing fields when
 * building breakdowns.
 *
 * @param symbol           the ticker (matches DB primary key)
 * @param companyName      from NASDAQ public lists, e.g. "Apple Inc."
 * @param listingExchange  "NASDAQ", "NYSE", "NYSE Arca", etc.
 * @param isEtf            true if NASDAQ flagged this as an ETF
 * @param roundLot         from IEX SecurityDirectory; default 100 if absent
 * @param prevCloseDollars from IEX SecurityDirectory's adjusted_poc_price
 * @param luldTier         IEX's Limit Up/Limit Down tier classification
 */
public record SymbolMetadata(
        String  symbol,
        String  companyName,
        String  listingExchange,
        Boolean isEtf,
        Integer roundLot,
        Double  prevCloseDollars,
        String  luldTier
) {}
