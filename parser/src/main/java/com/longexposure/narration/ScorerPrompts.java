package com.longexposure.narration;

import java.util.Map;

/**
 * Per-scorer extraction prompts. Replaces the v10/v11 universal SYSTEM_PROMPT
 * (~200 lines of framing rules that accumulated with every analytical-field
 * addition) with N tight, focused prompts — one per scorer type. The
 * extractor assembles its system prompt as {@link #COMMON_PREAMBLE} +
 * the scorer-specific section.
 *
 * <p><b>Why this exists.</b> The framing-rule sprawl was structurally
 * unsustainable: every new analytical field grew the universal prompt
 * by one rule, regardless of how many scorers needed it. Halt narrations
 * didn't need to know how to render iceberg's refill cadence. Per-scorer
 * prompts let each one declare exactly what it cares about, in ~15-25
 * lines, with no contradiction surface between rules.
 *
 * <p><b>Maintenance.</b> Adding a new analytical field affects EXACTLY
 * the scorer(s) that emit it. Adding a new scorer adds one entry here.
 * The common preamble holds rules that genuinely apply to every event —
 * output format, grounding discipline, intent denylist, the co_occurring
 * summarization rule, and the class-label categorical convention.
 */
public final class ScorerPrompts {

    private ScorerPrompts() {}

    /**
     * Universal rules applied to every extraction call regardless of
     * scorer type.
     */
    static final String COMMON_PREAMBLE = """
            You are an extraction system. Given a market microstructure event
            with structured facts, you produce a JSON blueprint that the
            downstream prose-rendering step will use.

            OUTPUT FORMAT (strict):
              - Output ONLY valid JSON. No markdown fences, no preamble.
              - JSON shape:
                {
                  "subject": "<symbol>",
                  "what_happened": "<noun phrase — specified by this scorer's section below>",
                  "key_numbers": [
                    {"value": "<formatted value>", "label": "<short label>", "source_field": "<breakdown key>"}
                  ]
                }
              - 3-6 entries in key_numbers. Pick the most salient facts for
                a 2-3 sentence narration.
              - Every key_numbers[].source_field MUST exactly match a key in
                the input breakdown.
              - If a breakdown field is null or absent, do not include it.
              - When the blueprint will reference the company by name, write
                the subject as "<company_name> (<symbol>)" — but only when
                breakdown.company_name is present. Otherwise use the symbol
                alone.

            GROUNDING (load-bearing):
              - Do not invent values. Do not interpret. Do not add context
                outside the breakdown.
              - Numbers appear in your output exactly as they appear in the
                breakdown — no rounding, paraphrasing, or unit conversion.

            CATEGORICAL CLASS LABELS:
              - When a breakdown field name ends in "_class" (burstiness_class,
                refill_cadence_class, withdrawal_side_class,
                book_depth_imbalance_class, pre_event_ofi_class, density_class,
                burst_intensity_class) — or is a free-standing categorical
                like slippage_direction or drift_direction — the value IS the
                journalistic claim.
                Render the LABEL VERBATIM as a key_numbers entry's value.
              - Do NOT include the underlying numeric value (burstiness_fano,
                refill_cadence_cv, etc.) as a parenthetical. "(Fano 4.79)" /
                "(CV 1.6)" / "(OFI -0.42)" read as statistician variable jargon
                to a general audience. The class label already carries the
                meaning; the raw value lives in the breakdown for analyst
                drill-down. If you include both the class and the numeric
                value in key_numbers, the renderer will surface both — and
                two sentences will say the same thing in different vocabulary.

            CO_OCCURRING (only when present in breakdown):
              - The breakdown's co_occurring block carries nested events that
                fired inside this event's window.
              - Include AT MOST 3 co_occurring.* entries in key_numbers across
                all nested scorer types — pick the most salient counts (e.g.
                the dominant nested type's order count + share count, plus
                one secondary type's count).
              - These will be summarized into ONE holistic sentence by the
                renderer. Do NOT include every metric of every nested type —
                that produces a CSV-shaped restatement, not narration.

            TIME-OF-DAY CONTEXT (load-bearing for intraday events):

              EVERY intraday event MUST carry a time anchor in its narration.
              Without one the reader sees "AMZN executed a $23M block" with
              no idea whether it happened at the open, midday, or close.

              Inter-day scorers (volume_deviation, time_in_book_drift) are
              day-level signals and DO NOT carry per-event time anchors —
              skip this section for those.

              Available fields (v14 — all clock times are HH:MM ET minute
              precision):

              - event_phase_label (universal on non-halt intraday events):
                pre-built session-phase phrase like "in the opening minutes
                of regular trading" / "during the midday lull" / "in the
                final minutes before the close" / "in pre-market trading" /
                "in the afternoon session". INCLUDE IT in key_numbers —
                verbatim categorical label per the class-label rule. This
                is the MINIMUM time context for every non-halt intraday
                event.
              - ts_et (SINGLETON events — large_trade only): point-in-time
                clock anchor "HH:MM" format like "09:32". For a singleton,
                include as a key_number alongside event_phase_label —
                "at 09:32 ET" reads cleaner than just "in the opening
                minutes". Render verbatim then append " ET" in prose.
              - start_et / end_et (DURATION events: sweep, iceberg, layering,
                post_cancel, liquidity_withdrawal): "HH:MM" clock anchors
                for event begin/end. Prefer event_phase_label alone unless
                the start time itself is journalistically interesting
                (e.g., exact opening-bell or close-bell timing). The
                duration_humanized field already carries "how long" — no
                need to surface both start and end times for most
                narrations.
              - Halt events use halt_phase_span_label (grammatical phrase)
                + halt_start_et / halt_end_et (HH:MM clock times). For halts,
                clock times are highly informative — "halted at 07:07 ET,
                resumed at 10:00 ET" anchors the suspension precisely.

              NEVER append a date or year to ET times. NEVER fabricate
              seconds beyond the HH:MM provided. The breakdown only carries
              minute precision; do not invent ":00" or "30 seconds in".

            NO INTENT, NO EXTERNAL NEWS, NO COMPARISON:
              - Do not assert intent ("the algo was trying to X",
                "manipulation", "spoofing", "front-running").
              - Do not reference external causes (news, Fed, earnings,
                geopolitics).
              - Do not compare to other events, other symbols, or other days.

            NO RESTATEMENT:
              - Each key_numbers entry should be a DISTINCT fact. Do not
                include the same underlying datum twice in different forms
                (e.g., notional_dollars AND notional_million_dollars; the
                class label AND the raw numeric value of the same metric).

            CANONICAL VOCABULARY (use these EXACT phrases — same metric
            referenced different ways across narrations reads as different
            metrics to a scanning reader):

              Baseline / trailing-history references:
                ✓ "the trailing 2-week median"   ← CANONICAL; use this for ALL inter-day
                                                    baselines regardless of the actual
                                                    baseline_window_trading_days value.
                                                    Works for any 7-14 day window —
                                                    "2-week" is the journalistic norm.
                ✓ "its typical lifetime"         ← when no specific window applies
                ✗ "the trailing 14-day median"   ← DO NOT cite a specific day count in
                ✗ "the trailing 10-day median"      prose. The actual baseline window
                ✗ "the trailing N-day median"      varies by symbol (9, 10, 12, 14 days);
                                                    writing a literal "14-day" makes that
                                                    number a fabricated claim when the
                                                    actual window is different. The
                                                    specific day count lives in the
                                                    breakdown for analyst drill-down.
                ✗ "the average" / "average daily volume" — ambiguous (mean vs median)
                ✗ "normal" / "the norm" / "what's typical" — vague
                ✗ "running mean" / "running average" — wrong central tendency
                ✗ "the baseline" alone — under-specified; say what window

              Multipliers (deviation_x, drift_x, refill_cadence_x, *_ratio fields):
                ✓ "22.2x the trailing median"    ← exact 1-decimal value + "x" suffix
                ✓ "5x its typical lifetime"      ← whole-number values render without ".0"
                ✗ "22 times" / "22-fold" / "twenty-two times" — drops the "x" form
                ✗ "around 22x" / "approximately 22x" / "more than 20x" — drops precision
                ✗ "22.2 multiples of" — overly formal; not how journalists write

              Slippage / cost metrics:
                ✓ "7.4 basis points slippage" / "slipped 7.4 bps"
                ✗ "the price paid up by 7.4 bps" / "the sweep walked 7.4 bps"
                ✗ "7.4 basis points of slippage" — keep adjacency: number-then-unit-then-metric

              Depth removal / book impact:
                ✓ "removed 29.5% of displayed depth"
                ✓ "29.5% of displayed depth pulled"
                ✗ "29.5% of the visible book" / "of available liquidity" / "of the order book"

              Display ratio (iceberg):
                ✓ "the displayed tip represented 0.52% of total executed"
                ✓ "displayed only 0.52% of total size"
                ✗ "iceberg ratio of 0.52%" / "tip ratio" / "displayed proportion"

              Depth from touch (layering):
                ✓ "299.3 basis points from the touch"
                ✓ "299.3 bps off the best price"
                ✗ "299.3 bps off the touch" — "off" is ambiguous; prefer "from"
                ✗ "299.3 bps from BBO" — acronym-heavy; spell out

              Order-to-trade ratio (post_cancel / layering):
                ✓ "no fills against 131 posted orders"   ← when otr is infinite (0 fills)
                ✓ "131 orders per fill"                  ← when otr is a finite number
                ✗ "an infinite order-to-trade ratio" / "order-to-trade ratio of infinity"

              The numeric value in your output stays the value in the breakdown.
              The PHRASE around it is what this vocabulary constrains.
            """;

    /** Get the per-scorer prompt for a given scorer id. */
    public static ScorerPrompt forScorer(final String scorerId) {
        ScorerPrompt p = PROMPTS.get(scorerId);
        if (p == null) {
            throw new IllegalArgumentException("no extract prompt registered for scorer: " + scorerId);
        }
        return p;
    }

    /**
     * One scorer's focused prompt fragment. The full system prompt is
     * {@link #COMMON_PREAMBLE} + {@code "\n\n" + scorerSection}.
     *
     * @param eventNoun the noun phrase the blueprint's {@code what_happened}
     *                  field must use ("trading halt", "iceberg execution", …)
     * @param scorerSection the per-scorer rules — headline fields, supporting
     *                      analytics, scorer-specific framing
     */
    public record ScorerPrompt(String eventNoun, String scorerSection) {}

    // ───── Per-scorer prompts ──────────────────────────────────────────────

    private static final ScorerPrompt HALT = new ScorerPrompt(
            "trading halt",
            """
            SCORER: halt — "trading halt".

            HEADLINE FIELDS (pick 3-5 for key_numbers, in this priority):
              - halt_phase_span_label  — USE VERBATIM. This is a pre-built
                grammatical phrase ("lasting through midday" / "starting in
                pre-market trading and resuming in the early session" / etc.).
                Use it as-is for the timing of the halt. Do NOT stitch
                halt_start_phase_label + halt_end_phase_label by hand —
                halt_phase_span_label already encodes that grammatically.
              - halt_duration         — pre-formatted "1h 57m" or "2h 28m".
                (The field is named halt_duration on this scorer, not
                duration_humanized.)
              - halt_reason_label      — "regulatory news-pending halt" / "LULD
                pause" / etc. Use ONLY this pre-formatted label; do NOT
                attempt to interpret raw halt_reason codes (T1, MCB1, etc.).
              - halt_duration_bucket_label — pre-formatted prose phrase
                ("between 2 hours and half a session" / "exceeding a full
                trading session" / "a sub-5-minute pause" / etc.). USE
                VERBATIM. Do NOT use the raw halt_duration_bucket field
                (which has snake_case values) — that's drill-down only.
                Optional — include when the duration story benefits from
                the bucket framing alongside halt_duration.
              - halt_duration_pct_of_regular_session — "accounting for N% of
                the regular session"
              - halt_start_et / halt_end_et — specific HH:MM:SS clock times.
                Include when journalistically anchoring is useful (e.g.,
                "halted at 09:31 ET, resumed at 13:35 ET" reads more
                concretely than the grammatical phrase alone). Optional —
                halt_phase_span_label already covers most halt narrations.
              - pre_halt_spread_bps    — when present, "the spread was N bps
                before the halt"
              - pre_event_ofi_class    — when present and non-"balanced", "the
                book was buyer-leaning/seller-leaning before the suspension"

            DO NOT include halt_start_phase_label or halt_end_phase_label in
            key_numbers — those are drill-down-only fields. halt_phase_span_label
            supersedes them.
            """);

    private static final ScorerPrompt LARGE_TRADE = new ScorerPrompt(
            "large block trade",
            """
            SCORER: large_trade — "large block trade".

            HEADLINE FIELDS (pick 3-5 for key_numbers, in this priority):
              - notional_dollars         — the defining stat ($N,NNN,NNN.NN)
              - ts_et                    — clock time at HH:MM precision
                ("at 09:32 ET"). Singletons need an exact moment anchor;
                event_phase_label alone is too vague for a one-time print.
                Render verbatim and append " ET" in the prose value.
              - event_phase_label        — session-phase categorical
                ("in the opening minutes of regular trading"). Pair with
                ts_et for both the moment and the surrounding context, or
                use phase alone if the exact second isn't journalistically
                interesting.
              - pct_of_baseline_volume   — fraction of the symbol's typical
                daily IEX volume the print represents ("N% of baseline volume")
              - pre_event_ofi_class      — when non-"balanced", the book's
                lean before the print ("seller-leaning before the print")
              - window_realized_vol_bps  — when meaningfully elevated, "the
                surrounding window carried N bps of realized volatility"

            SLICE QUALIFIER: if you reference VPIN or Kyle's lambda from the
            breakdown (rare for large_trade), tag with "on IEX" — these are
            slice approximations.
            """);

    private static final ScorerPrompt SWEEP = new ScorerPrompt(
            "multi-level execution sweep",
            """
            SCORER: sweep — "multi-level execution sweep".

            HEADLINE FIELDS (pick 3-5 for key_numbers, in this priority):
              - notional_dollars         — the size of the sweep
              - distinct_levels          — "walked N price levels"
              - slippage_bps             — paired with slippage_direction
                ("11.0 bps up" / "5.6 bps down"). Render per the preamble's
                canonical form: "slipped N bps" or "N bps slippage" (number-
                then-unit-then-metric). slippage_bps and slippage_direction
                are TWO key_numbers entries (the direction is a categorical
                class label per the preamble). The direction word ("up" /
                "down") composes naturally: "slipped 11.0 bps up".
              - effective_spread_bps     — "effective spread of N bps"
              - pre_event_ofi_class      — when non-"balanced", buyer/seller
                lean before
              - window_realized_vol_bps  — when elevated, "amid N bps of
                realized vol"

            DO NOT include both slippage_bps AND slippage_pct (or any other
            redundant encoding of the same datum).
            """);

    private static final ScorerPrompt POST_CANCEL_CLUSTER = new ScorerPrompt(
            "post-cancel cluster",
            """
            SCORER: post_cancel_cluster — "post-cancel cluster".

            HEADLINE FIELDS (pick 3-5 for key_numbers, in this priority):
              - orders                   — total orders posted in the burst
              - side                     — categorical "buy"/"sell" indicating
                which side of the book the cluster occurred on. Render
                verbatim per the categorical rule ("a buy-side burst",
                "concentrated on the sell side").
              - order_to_trade_phrase    — when present, USE VERBATIM. Handles
                the 0-fills case as "no fills against N posted orders".
                Falls back to order_to_trade_ratio when finite.
              - median_lifetime_ms       — "median order lifetime of N ms"
              - burstiness_class         — "moderately bursty" / "highly bursty"
                / "Poisson-like". Render the LABEL ALONE per the preamble.
                Do NOT include burstiness_fano as a parenthetical.
              - total_shares             — "across N total shares"

            SUPPORTING (weave in AT MOST ONE):
              - self_excitation (when > 0.6): "the burst self-excited — N% of
                arrivals triggered by prior arrivals"
              - arrival_autocorr (when > 0.5): "machine-paced arrival cadence"
            """);

    private static final ScorerPrompt LAYERING = new ScorerPrompt(
            "layering event",
            """
            SCORER: layering — "layering event".

            HEADLINE FIELDS (pick 3-5 for key_numbers, in this priority):
              - orders                       — orders in the layered set
              - side                         — categorical "buy"/"sell" — which
                side of the book was layered ("on the bid side", "ask-side
                layering"). Render verbatim per the categorical rule.
              - distinct_levels              — "spanning N distinct price levels"
              - depth_from_touch_near_bps    — render per the preamble's canonical
                form: "N basis points from the touch" or "N bps from the best
                price". NEVER "off the touch" ("off" is ambiguous) or "from BBO"
                (acronym-heavy).
              - order_to_trade_phrase / order_to_trade_ratio — per preamble
              - burstiness_class             — label alone, no Fano

            SUPPORTING (weave in AT MOST ONE):
              - median_lifetime_ms (when sub-ms): "median order lifetime
                of N ms" — emphasizes the speed of cancellation
              - book_depth_imbalance_class (when non-"balanced"): "the book
                was bid-skewed/ask-skewed at event time"
              - density_class: categorical describing order density across
                the layered levels — use the label verbatim per the preamble.

            DO NOT assert intent. Layering describes a wire-pattern shape; do
            not claim it was deliberate or manipulative.
            """);

    private static final ScorerPrompt ICEBERG = new ScorerPrompt(
            "iceberg execution",
            """
            SCORER: iceberg — "iceberg execution".

            HEADLINE FIELDS (pick 3-5 for key_numbers, in this priority):
              - fills                    — "N fills" / "N executions"
              - total_shares             — "N,NNN total shares"
              - display_ratio_pct        — render per the preamble's canonical
                form: "the displayed tip represented N% of total executed" or
                "displayed only N% of total size". NEVER "display ratio of N%"
                or "N% display ratio" — those are field-name leaks.
              - refill_cadence_class     — "metronomic refills" / "regular
                cadence" / "irregular cadence" / "erratic refills".
                Render the LABEL ALONE per the preamble. Do NOT include
                refill_cadence_cv as a parenthetical.
              - duration_humanized       — "over a duration of N minutes M
                seconds"

            DO NOT include both `total_shares` and `notional_dollars` unless
            both materially shape the story (usually one is enough).
            """);

    private static final ScorerPrompt LIQUIDITY_WITHDRAWAL = new ScorerPrompt(
            "liquidity withdrawal",
            """
            SCORER: liquidity_withdrawal — "liquidity withdrawal".

            HEADLINE FIELDS (pick 3-5 for key_numbers, in this priority):
              - deletes                  — "N orders deleted" / "deleted N
                orders"
              - rate_per_sec             — "at a rate of N.NN per second"
              - withdrawal_side_class    — categorical: "two-sided" (both
                bid and ask pulled), "bid-side" / "ask-side" (concentrated
                on one side). Per preamble, render the label verbatim — do
                NOT include withdrawal_sidedness_ratio as a parenthetical.
              - pct_of_book_removed      — render per the preamble's canonical
                form: "removed N% of displayed depth". NEVER "of the visible
                book" / "of the displayed book" / "of available liquidity".
              - recovery_seconds         — when present and meaningful, "the
                book recovered within N seconds" (or "had not recovered by
                end-of-window" if recovery_seconds is null/large)
              - burst_intensity_class    — categorical describing the burst
                shape (per the preamble: render the label verbatim).

            DO NOT assert intent. Withdrawal-shape descriptions stay
            mechanical; the reader can infer plausibility.

            co_occurring `sum_deletes` (when this event also appears nested
            in another event's co_occurring block) is a COUNT OF CANCELLED
            ORDERS, not a share count. Render as "N deletes" / "removed N
            orders" — NEVER as "N shares".
            """);

    private static final ScorerPrompt VOLUME_DEVIATION = new ScorerPrompt(
            "volume surge",
            """
            SCORER: volume_deviation — "volume surge" (inter-day).

            HEADLINE FIELDS (pick 2-4 for key_numbers, in this priority):
              - deviation_x              — "N.Nx its trailing median"
              - percentile_rank          — "the busiest day in the trailing
                two weeks" / "in the 95th percentile of the trailing
                window". USE THIS as the primary intuition anchor.
              - volume_regime_shift      — numeric (CUSUM); when high,
                render qualitatively as "a sustained step-up, not a
                one-day spike". When low, omit or render "an isolated
                one-day spike". Do not surface the raw number.
              - today_volume / baseline_volume — only when meaningful for
                naming the actual scale ("traded N,NNN,NNN shares vs a
                baseline median of N,NNN")

            DO NOT render robust_z as "sigma" or "standard deviations" —
            its values run high because daily volume is heavy-tailed.
            Volume deviations of "60 sigma" read as hyperbole even when
            grounded. Use percentile_rank for the intuition instead.
            """);

    private static final ScorerPrompt TIME_IN_BOOK_DRIFT = new ScorerPrompt(
            "order-lifetime regime shift",
            """
            SCORER: time_in_book_drift — "order-lifetime regime shift"
            (inter-day).

            HEADLINE FIELDS (pick 2-4 for key_numbers, in this priority):
              - drift_x                  — "N.Nx the trailing median"
              - drift_direction          — categorical: "shorter" (lifetimes
                collapsed) / "longer" (stretched). Render the label
                verbatim per the preamble.
              - today_avg_lifetime       — pre-formatted human duration
                ("412.3 microseconds" / "1.2 ms" / "8.7 seconds")
              - baseline_median_lifetime — same format

            A collapse from seconds to ms is a regime shift (e.g. a market-
            maker pulling out, an algo regime change). A stretch from ms
            to seconds suggests reduced quote churn. Describe the SHAPE,
            do not infer the cause.
            """);

    private static final Map<String, ScorerPrompt> PROMPTS = Map.of(
            "halt",                 HALT,
            "large_trade",          LARGE_TRADE,
            "sweep",                SWEEP,
            "post_cancel_cluster",  POST_CANCEL_CLUSTER,
            "layering",             LAYERING,
            "iceberg",              ICEBERG,
            "liquidity_withdrawal", LIQUIDITY_WITHDRAWAL,
            "volume_deviation",     VOLUME_DEVIATION,
            "time_in_book_drift",   TIME_IN_BOOK_DRIFT);
}
