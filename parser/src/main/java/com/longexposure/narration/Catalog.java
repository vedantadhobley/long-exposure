package com.longexposure.narration;

import java.util.List;
import java.util.Map;

/**
 * Pattern catalog content. The canonical source-of-truth is
 * {@code parser/src/main/resources/pattern-catalog.md} — that's the
 * human-readable + reviewable form. This class mirrors the catalog's
 * factual mechanism + multi-driver lists per scorer for runtime
 * consumption, kept in sync with the markdown file.
 *
 * <h2>2026-05-31 (v15) — Entry refactor: drivers list, not prose template</h2>
 *
 * <p>Earlier version had a {@code canonicalInterpretation} prose string per
 * scorer. {@code InterpretEventActivityImpl} injected that string verbatim
 * into the LLM prompt as the documented driver reference. The model treated
 * it as a TEMPLATE, copying its dramatic vocabulary into every output.
 *
 * <p>The worst offender was {@code time_in_book_drift} whose
 * canonicalInterpretation started "describes a regime shift…". As a result,
 * 19/19 drift interpretations on 2026-05-22 contained "regime shift", which
 * cascaded into SYNTHESIZE writing "the day was defined by a pervasive
 * regime shift…" — formulaic across 6 of 11 days.
 *
 * <p>v15 replaces {@code canonicalInterpretation} with {@code documentedDrivers}
 * — a {@link List} of factual driver descriptions, no prose template the
 * model can copy. The prompt now renders these as a bulleted list of
 * mechanism options; the model writes its own framing using its general
 * journalism knowledge. Drivers retain the "multi-driver" discipline (every
 * pattern has 2+ legitimate explanations, never a single intent claim).
 */
public final class Catalog {

    private Catalog() {}

    /** Per-scorer entries. Keys match the {@code EventScorer.id()} values. */
    public static final Map<String, Entry> ENTRIES = Map.of(
            "halt", new Entry(
                    "A trading suspension imposed by an exchange. Three regulatory causes drive halts: news pending (T1), "
                  + "single-stock circuit breaker pause (LULD), or market-wide circuit breaker (MCB).",
                    List.of(
                            "material news pending (T1)",
                            "single-stock LULD volatility pause",
                            "market-wide circuit breaker (MCB)"
                    )),

            "large_trade", new Entry(
                    "A single execution exceeding $1M in notional value (size × price). On IEX, can be a routed order filling against "
                  + "displayed liquidity, a non-displayed match in the dark pool, or a mid-point execution against pegged order types.",
                    List.of(
                            "institutional position changes",
                            "ETF flows",
                            "index or basket rebalances",
                            "hedging against derivative exposure"
                    )),

            "sweep", new Entry(
                    "A single aggressive order executing against multiple resting price levels in rapid succession, visible as a burst "
                  + "of OrderExecuted messages walking the book from the best price upward (for buys) or downward (for sells).",
                    List.of(
                            "information-based urgency",
                            "hedge unwinding",
                            "basket-trading execution where speed dominates price"
                    )),

            "iceberg", new Entry(
                    "A pattern of repeated equal-size fills at one price level with consistent inter-fill timing and a constant "
                  + "displayed size. The underlying instrument is a reserve order whose displayed tip is a fraction of the total size.",
                    List.of(
                            "institutional execution seeking minimal market impact",
                            "algorithmic VWAP or participation-rate strategies",
                            "market-making activity"
                    )),

            "layering", new Entry(
                    "Multiple orders posted across distinct price levels on one side of the book, followed by rapid cancellation. "
                  + "Detected as OrderAdd events at five or more distinct price levels followed by OrderDelete events on the same "
                  + "orders within a short window.",
                    List.of(
                            "market-making quote adjustments",
                            "algorithmic price-discovery probes",
                            "risk-management responses to correlated moves",
                            "spoofing (when documented as such by regulators — not inferable from wire data alone)"
                    )),

            "post_cancel_cluster", new Entry(
                    "A burst of orders placed and quickly cancelled — same shape as layering but without requiring distribution across "
                  + "multiple price levels. Detected as paired OrderAdd / OrderDelete events with lifetime under 50 ms, clustered in time.",
                    List.of(
                            "market-maker quote updates following NBBO changes",
                            "smart-order-router probes",
                            "rapid hedging adjustments"
                    )),

            "liquidity_withdrawal", new Entry(
                    "A flood of cancellations on a single symbol within a narrow window — detected as 50 or more OrderDelete events "
                  + "on the same symbol with under-100ms gaps between consecutive cancels. The book's displayed depth contracts visibly "
                  + "during the withdrawal.",
                    List.of(
                            "defensive market-maker behavior ahead of anticipated volatility",
                            "response to single-stock LULD bands tightening",
                            "response to correlated-instrument moves"
                    )),

            // ─── Inter-day scorers ─────────────────────────────────────────
            // Inter-day INTERPRET reads only the breakdown (the day's metric,
            // the baseline median, and the deviation magnitude). No ±60-sec
            // window query — the temporal framing doesn't apply to a whole-
            // day signal.

            "volume_deviation", new Entry(
                    "A day-level deviation in IEX-observed volume on a single symbol versus its trailing-window median, "
                  + "computed against the daily_volume_by_symbol cagg (400-day refresh window). Surfaces when today's volume "
                  + "is N× the trailing median by a configured threshold.",
                    List.of(
                            "news-driven attention on the symbol",
                            "sector or ETF-family flow",
                            "index or basket rebalance",
                            "single-name capital deployment",
                            "algorithmic-strategy entry or exit"
                    )),

            "time_in_book_drift", new Entry(
                    "A day-level shift in the per-symbol average order lifetime (how long the typical order rests on the book "
                  + "before delete or execute) versus its trailing-window median, computed against the daily_lifetime_by_symbol "
                  + "table. Drift_x measures the symmetric magnitude in either direction (lifetimes shorter or longer).",
                    List.of(
                            "shorter lifetimes — high-frequency algorithmic participants increasing activity",
                            "shorter lifetimes — market-makers cycling quotes rapidly in response to volatility",
                            "longer lifetimes — HFT withdrawal during quiet hours",
                            "longer lifetimes — post-volatility cool-down period",
                            "longer lifetimes — institutional-strategy entry resting passively"
                    ))
    );

    /**
     * Look up an entry by scorer id. Returns null if no catalog entry exists
     * for that scorer (caller should skip Layer-0 generation for that event).
     */
    public static Entry forScorer(final String scorerId) {
        return ENTRIES.get(scorerId);
    }

    /**
     * A single catalog entry.
     *
     * @param mechanism          factual description of what the pattern IS at
     *                           the wire level (what the scorer detects)
     * @param documentedDrivers  list of legitimate explanations for this
     *                           pattern. Multi-driver discipline: every
     *                           pattern has 2+ legitimate causes. INTERPRET
     *                           injects these as a bulleted list of options
     *                           the model picks among when contextualizing
     *                           an event. NO prose template the model copies.
     */
    public record Entry(String mechanism, List<String> documentedDrivers) {}
}
