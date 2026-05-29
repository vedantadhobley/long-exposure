package com.longexposure.narration;

import java.util.Map;

/**
 * Pattern catalog content. The canonical source-of-truth is
 * {@code parser/src/main/resources/pattern-catalog.md} — that's the
 * human-readable + reviewable form. This class mirrors the catalog's
 * canonical interpretation field per scorer for runtime consumption,
 * kept in sync with the markdown file.
 *
 * <p>During the Layer-0 smoke test (Option A prototype) this class
 * supplies the catalog entry for each event. If Option A wins, this
 * stays. If Option B wins, the templated_interpretation field will
 * be added per scorer, also mirrored from the markdown.
 *
 * <p>Update process: when the markdown catalog changes, update this
 * file to match. Future improvement: write a markdown parser that
 * loads the catalog at runtime from the resource file. Not needed
 * for the smoke test.
 */
public final class Catalog {

    private Catalog() {}

    /** Per-scorer entries. Keys match the {@code EventScorer.id()} values. */
    public static final Map<String, Entry> ENTRIES = Map.of(
            "halt", new Entry(
                    "A trading suspension imposed by an exchange. Three regulatory causes drive halts: news pending (T1), "
                  + "single-stock circuit breaker pause (LULD), or market-wide circuit breaker (MCB).",
                    "Trading halts can result from material news pending, single-stock circuit breaker volatility pauses (LULD), "
                  + "or market-wide circuit breakers (MCB). The wire data records the duration and reason code but not the "
                  + "underlying news or market condition that triggered the suspension."),

            "large_trade", new Entry(
                    "A single execution exceeding $1M in notional value (size × price). On IEX, can be a routed order filling against "
                  + "displayed liquidity, a non-displayed match in the dark pool, or a mid-point execution against pegged order types.",
                    "Large single prints can reflect institutional position changes, ETF flows, index rebalances, or hedging against "
                  + "derivative exposure. The wire data records the size and price but not the originating strategy."),

            "sweep", new Entry(
                    "A single aggressive order executing against multiple resting price levels in rapid succession, visible as a burst "
                  + "of OrderExecuted messages walking the book from the best price upward (for buys) or downward (for sells).",
                    "Multi-level sweeps consume displayed liquidity at multiple price levels in rapid succession, indicating the "
                  + "originator prioritized immediate fill over price improvement. Drivers include information-based urgency, hedge "
                  + "unwinding, or basket-trading execution where speed dominates price."),

            "iceberg", new Entry(
                    "A pattern of repeated equal-size fills at one price level with consistent inter-fill timing and a constant "
                  + "displayed size. The underlying instrument is a reserve order whose displayed tip is a fraction of the total size.",
                    "Iceberg orders display only a fraction of their total size — the visible tip refills automatically as it fills. "
                  + "This pattern is associated with institutional execution seeking minimal market impact, algorithmic VWAP or "
                  + "participation-rate strategies, and market-making activity. The wire data records the displayed fills but not the "
                  + "underlying reserve."),

            "layering", new Entry(
                    "Multiple orders posted across distinct price levels on one side of the book, followed by rapid cancellation. "
                  + "Detected as OrderAdd events at five or more distinct price levels followed by OrderDelete events on the same "
                  + "orders within a short window.",
                    "Layering describes orders posted across multiple price levels and cancelled rapidly. The same wire signature is "
                  + "produced by market-making quote adjustments, algorithmic price-discovery probes, risk-management responses to "
                  + "correlated moves, and (when documented as such by regulators) spoofing. The wire data records the shape but not "
                  + "the originator's intent."),

            "post_cancel_cluster", new Entry(
                    "A burst of orders placed and quickly cancelled — same shape as layering but without requiring distribution across "
                  + "multiple price levels. Detected as paired OrderAdd / OrderDelete events with lifetime under 50 ms, clustered in time.",
                    "Post-cancel clusters describe rapid bursts of orders posted and cancelled within tens of milliseconds. Common "
                  + "drivers include market-maker quote updates following NBBO changes, smart-order-router probes, and rapid hedging "
                  + "adjustments. The wire data records the activity shape but not whether it was strategy execution, market-making, "
                  + "or directed activity."),

            "liquidity_withdrawal", new Entry(
                    "A flood of cancellations on a single symbol within a narrow window — detected as 50 or more OrderDelete events "
                  + "on the same symbol with under-100ms gaps between consecutive cancels. The book's displayed depth contracts visibly "
                  + "during the withdrawal.",
                    "Liquidity withdrawals describe a rapid contraction of displayed depth on a symbol — typically defensive "
                  + "market-maker behavior ahead of anticipated volatility from news, single-stock circuit breaker (LULD) bands, "
                  + "or correlated-instrument moves. The wire data records the cancellation pattern but not the specific information "
                  + "or event the market makers were responding to."),

            // ─── Inter-day scorers (Phase 9-A, 2026-05-28) ─────────────────────
            // Inter-day INTERPRET reads only the breakdown (which carries the
            // day's metric, the baseline median, and the deviation magnitude).
            // No ±60-sec window query — the temporal framing doesn't apply to a
            // whole-day signal. The InterpretEventActivityImpl branches on
            // scorerId to use a different prompt section for these.

            "volume_deviation", new Entry(
                    "A day-level deviation in IEX-observed volume on a single symbol versus its trailing-window median, "
                  + "computed against the daily_volume_by_symbol cagg (400-day refresh window). Surfaces when today's volume "
                  + "is N× the trailing median by a configured threshold.",
                    "Volume deviations describe a symbol whose IEX trading volume today differs materially from its trailing-window "
                  + "norm. Documented drivers include news-driven attention, sector or ETF-family flow, index/basket rebalances, "
                  + "single-name capital deployment, and algorithmic-strategy entry or exit. The wire data records the symbol's "
                  + "IEX activity but not the underlying news, sector rotation, or capital event."),

            "time_in_book_drift", new Entry(
                    "A day-level shift in the per-symbol average order lifetime (how long the typical order rests on the book "
                  + "before delete or execute) versus its trailing-window median, computed against the daily_lifetime_by_symbol "
                  + "table. Drift_x measures the symmetric magnitude in either direction (lifetimes shorter or longer).",
                    "Time-in-book drift describes a regime shift in how long orders rest on a symbol's book on IEX. Shorter "
                  + "lifetimes are consistent with high-frequency algorithmic participants increasing activity (post-cancel "
                  + "patterns dominate) or market-makers cycling quotes rapidly. Longer lifetimes are consistent with HFT "
                  + "withdrawal, post-volatility cool-down, or institutional-strategy entry resting passively. The wire data "
                  + "records the lifetime distribution but not which participant class produced the shift.")
    );

    /**
     * Look up an entry by scorer id. Returns null if no catalog entry exists
     * for that scorer (caller should skip Layer-0 generation for that event).
     */
    public static Entry forScorer(final String scorerId) {
        return ENTRIES.get(scorerId);
    }

    /**
     * A single catalog entry. {@link #mechanism} is the factual description of
     * what the pattern IS at the wire level. {@link #canonicalInterpretation}
     * is the safe one-sentence Layer-0 prose, the source of vocabulary for
     * the LLM and the substrate for Option B's template (when added).
     */
    public record Entry(String mechanism, String canonicalInterpretation) {}
}
