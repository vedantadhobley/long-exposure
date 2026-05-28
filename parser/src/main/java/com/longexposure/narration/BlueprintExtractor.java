package com.longexposure.narration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.longexposure.llm.LlamaClient;
import com.longexposure.llm.SamplingParams;

import java.util.Iterator;
import java.util.Map;

/**
 * Pass 1 of the two-pass narration model. Takes a {@code selected_events}
 * row (its scorer_id + symbol + breakdown JSON) and asks the LLM to emit
 * a JSON "blueprint" listing the facts that will be narrated, each
 * mapped to its {@code source_field} in the breakdown.
 *
 * <p>Strict grounding contract: every entry in {@code key_numbers[]}
 * MUST cite a real field in the input breakdown via {@code source_field}.
 * The downstream verifier checks this.
 *
 * <p>This class is pure logic — it owns the prompt template and calls
 * the supplied {@link LlamaClient}. The Temporal activity wraps it for
 * scheduling / retries.
 */
public final class BlueprintExtractor {

    /** Bump this string when the prompt or output shape changes. Used in the event_hash. */
    public static final String PROMPT_VERSION = "extract-v8-cadence-class";

    private static final String SYSTEM_PROMPT = """
            You are an extraction system. Given a market microstructure event with structured facts,
            you produce a JSON blueprint that the downstream prose-rendering step will use.

            STRICT RULES:
            - Output ONLY valid JSON, no markdown fences, no preamble.
            - Every key_numbers[].source_field MUST exactly match a key in the input breakdown.
            - Do not invent values. Do not interpret. Do not add context outside the breakdown.
            - If a field in the breakdown is null, do not include it in key_numbers.

            JSON shape:
            {
              "subject": "<symbol>",
              "what_happened": "<short noun phrase describing the event kind, e.g. 'large block trade', 'halt', 'multi-level execution sweep'>",
              "key_numbers": [
                {"value": "<value as it should appear in prose>", "label": "<short label like 'duration' or 'notional'>", "source_field": "<key in breakdown JSON>"}
              ]
            }

            Include between 3 and 6 entries in key_numbers — pick the most salient facts for a 2-3 sentence narration.

            HEADLINE FIELDS BY EVENT TYPE — lead key_numbers with the DEFINING fields for the event
            type below (include them FIRST when present in the breakdown, then add supporting facts):
            - sweep:                notional_dollars, distinct_levels, slippage_bps, effective_spread_bps
            - large_trade:          notional_dollars, pct_of_baseline_volume
            - volume_deviation:     deviation_x, percentile_rank
            - liquidity_withdrawal: deletes, rate_per_sec, withdrawal_side_class, pct_of_book_removed
            - post_cancel_cluster:  orders, order_to_trade (use `order_to_trade_phrase` when present else `order_to_trade_ratio`), median_lifetime_ms, burstiness_class (with `burstiness_fano` as the parenthetical value)
            - layering:             orders, distinct_levels, depth_from_touch_near_bps, order_to_trade (use `order_to_trade_phrase` when present else `order_to_trade_ratio`)
            - iceberg:              fills, total_shares, display_ratio_pct, refill_cadence_class (with `refill_cadence_cv` as the parenthetical value)
            - halt:                 halt duration + reason; pre_halt_spread_bps when present

            SUPPORTING ANALYTICS — the breakdown carries deeper measures that sharpen the story
            when relevant. Weave in 1-2 when they meaningfully add context — never list mechanically.
            A paragraph that recites every metric reads like a CSV, not journalism; a paragraph
            with a single defining number and a sharpening context-metric reads like a journalist.
            The most useful supporting metric varies by scorer:
            - sweep:                   pre_event_ofi (was directional flow primed?), window_realized_vol_bps (was the surrounding window volatile?), book_depth_imbalance (was the book skewed pre-event?)
            - large_trade:             pre_event_ofi (informed flow signature?), window_realized_vol_bps
            - post_cancel_cluster:     self_excitation (cascading triggers vs independent), arrival_autocorr (machine-paced cadence vs random), book_depth_imbalance
            - layering:                self_excitation, arrival_autocorr, book_depth_imbalance
            - iceberg:                 arrival_autocorr (regular refill cadence?), book_depth_imbalance
            - liquidity_withdrawal:    book_depth_imbalance (was the withdrawal asymmetric?), pre_event_ofi
            - halt:                    pre_event_ofi (was the book bracing before the suspension?)
            - volume_deviation:        volume_regime_shift (sustained step shift vs one-day spike)

            Slice-fragile (window_vpin, window_kyle_lambda, window_jump_ratio) are IEX-slice
            approximations. Use only when the narrative truly needs them, and always with the
            "on IEX" qualifier.

            The `what_happened` phrase NAMES THE EVENT TYPE (from "Event type:") and is INDEPENDENT
            of which number leads — never let a leading notional value relabel a sweep as a "block
            trade". Use: sweep → "multi-level execution sweep"; large_trade → "large block trade";
            layering → "layering event"; post_cancel_cluster → "post-cancel cluster"; iceberg →
            "iceberg execution"; liquidity_withdrawal → "liquidity withdrawal"; halt → "trading
            halt"; volume_deviation → "volume surge".

            FRAMING RULES (apply in the `value` text; these change wording, not grounding):
            - robust_z, burstiness_fano, refill_cadence_cv, withdrawal_sidedness_ratio are
              DIMENSIONLESS. NEVER call robust_z "sigma" / "standard deviations" — its values run
              high because volume is heavy-tailed; render it as "far above its typical range" and let
              percentile_rank ("the busiest day in the trailing two weeks") carry the intuition.
            - withdrawal_side_class is CATEGORICAL: render "two_sided" as "two-sided" (both bid and
              ask pulled), "bid_side"/"ask_side" as "concentrated on the bid/ask side". Do not assert
              intent. (Categorical key_numbers carry the label text as their value.)
            - slippage_direction is CATEGORICAL ("up"/"down"/"flat"); pair it with slippage_bps
              (e.g. "walked 11.0 bps up across N levels").
            - burstiness_class is the ANCHORED LABEL ("highly bursty" / "moderately bursty" /
              "weakly bursty" / "Poisson-like"). Lead with the WORD and add the Fano number as
              a parenthetical for grounding — e.g. "highly bursty (Fano 9.4)" — not "burstiness
              of 9.43" with no scale anchor.
            - refill_cadence_class is the ANCHORED LABEL for iceberg inter-fill cadence
              ("metronomic" / "regular" / "irregular" / "erratic"). Lead with the WORD and
              add the CV as a parenthetical, e.g. "metronomic refills (CV 0.24)" or
              "irregular cadence (CV 1.6)". NEVER render the bare phrase "coefficient of
              variation" or "CV X" without the anchor word — those read as raw statistics
              vocabulary, not journalism.
            - order_to_trade_phrase is the narrator-friendly rendering for the 0-fills case
              (value like "no fills against 187 posted orders"); when present, USE IT verbatim
              instead of "order-to-trade ratio of infinite". Falls back to `order_to_trade_ratio`
              for finite values.
            - book-state stats (present only when the symbol had a live IEX book):
              depth_from_touch_near_bps/far_bps → "the layered band sat N-M bps off the touch";
              pre_halt_spread_bps → "the spread was N bps when trading halted";
              pct_of_book_removed → "pulled N% of displayed depth".
            - pre_event_ofi_class is the ANCHORED LABEL ("buyer-leaning"/"seller-leaning"/
              "balanced") for the OFI value. Lead with the WORD and add the bare value as
              parenthetical, e.g. "the book was seller-leaning (OFI −0.42) before the sweep"
              or "buyer-leaning (OFI 0.6) ahead of the print". When pre_event_ofi_class is
              "balanced", the book was roughly even — skip the metric (no signal).
            - window_realized_vol_bps is the surrounding-window realized volatility in basis
              points. Render as "realized vol ran N bps in the window" or context-anchored
              ("the surrounding minute carried N bps of vol, well above the day's baseline").
            - self_excitation ∈ [0, 1] is the Hawkes branching-ratio estimate: fraction of
              orders triggered by prior orders. > 0.6 = "the burst self-excited — N% of orders
              triggered by prior arrivals" (cascading shape); 0.3-0.6 = "moderate self-
              excitation"; near zero = "arrivals were largely independent". When near zero,
              omit (no signal).
              When > 0.6 and arrival_autocorr is also high, the cadence was both cascading
              AND machine-paced — say so.
            - arrival_autocorr ∈ [−1, +1] is the lag-1 autocorrelation of inter-arrival gaps.
              > 0.5 = "machine-paced (autocorr 0.7)" — a fixed-beat algo. Between −0.2 and
              +0.2 = "near-Poisson arrivals (autocorr ~0)" or omit. Highly negative is rare
              and not worth rendering.
            - book_depth_imbalance_class is the ANCHORED LABEL ("bid-skewed"/"ask-skewed"/
              "balanced") for the displayed-depth ratio at event-time. Lead with the WORD
              and add the bare value as parenthetical: "the book was bid-skewed (imbalance
              0.43)" or "displayed depth was ask-skewed before the print". When the class
              is "balanced", skip the metric.
            - volume_regime_shift is the CUSUM-style detector of a SUSTAINED step in a
              symbol's trailing-window volume. Use it to distinguish "today was a one-day
              spike" from "today marks a regime shift": "the surge was a sustained step, not
              a one-day spike" when the shift is high; "an isolated one-day spike" otherwise.
            """;

    private final LlamaClient llama;
    private final ObjectMapper json;

    public BlueprintExtractor(final LlamaClient llama) {
        this(llama, new ObjectMapper());
    }

    public BlueprintExtractor(final LlamaClient llama, final ObjectMapper json) {
        this.llama = llama;
        this.json  = json;
    }

    /**
     * @return parsed blueprint JsonNode. Throws if the model output is not parseable JSON.
     */
    public JsonNode extract(final String scorerId, final String symbol, final JsonNode breakdown) {
        String userPrompt =
                "Event type: " + scorerId + "\n" +
                "Symbol: " + symbol + "\n" +
                "Breakdown (input — every source_field must be a key here):\n" +
                breakdown.toPrettyString();

        // EXTRACT preset: low temperature + Qwen instruct base. Pass-1 wants
        // deterministic JSON, not prose variety, so presence_penalty is 0.
        String raw = llama.chat(SYSTEM_PROMPT, userPrompt, SamplingParams.EXTRACT);
        JsonNode blueprint = parseJson(raw);

        // Pass-through fields the renderer needs but the extractor
        // shouldn't waste tokens deciding about. Code injects these
        // deterministically after the LLM call returns:
        //
        //   1. company_name — the canonical company display string for the
        //      subject. Sourced from the breakdown (which SymbolFields already
        //      normalized via CompanyNameNormalizer). Pass-through ensures
        //      the renderer sees ground truth.
        //
        //   2. co_occurring key_numbers — every number from the breakdown's
        //      co_occurring block, flattened into the blueprint's
        //      key_numbers[] array with dotted source_field paths. Without
        //      this, the extractor's LLM consistently picks 3-6 "primary
        //      facts" from the top-level breakdown fields and never includes
        //      co_occurring numbers. The renderer's co_occurring slot then
        //      can't be populated because there are no co_occurring values
        //      in its input.
        if (blueprint instanceof ObjectNode obj) {
            JsonNode cn = breakdown.path("company_name");
            if (cn.isTextual() && !cn.asText().isBlank()) {
                obj.put("company_name", cn.asText());
            }
            injectCoOccurringKeyNumbers(obj, breakdown);
        }

        return blueprint;
    }

    /**
     * Walk the breakdown's {@code co_occurring} block and append a
     * {@code key_numbers[]} entry for every value found, with the
     * dotted {@code source_field} path. Idempotent and order-stable —
     * runs after the LLM has emitted its primary-fact key_numbers and
     * appends co_occurring numbers afterward.
     *
     * <p>For a breakdown with {@code co_occurring.during_event.layering.count = 1}
     * and {@code .sum_orders = 23}, this adds two entries:
     * <pre>
     * {"value":"1",  "label":"co-occurring layering count",
     *  "source_field":"co_occurring.during_event.layering.count"}
     * {"value":"23", "label":"co-occurring layering sum_orders",
     *  "source_field":"co_occurring.during_event.layering.sum_orders"}
     * </pre>
     * The renderer's prompt instructs it to put numbers whose
     * source_field starts with {@code co_occurring.} into the
     * {@code co_occurring} slot rather than {@code facts[]}.
     */
    private static void injectCoOccurringKeyNumbers(final ObjectNode blueprint, final JsonNode breakdown) {
        JsonNode coOccurring = breakdown.path("co_occurring");
        if (coOccurring.isMissingNode() || coOccurring.isNull() || !coOccurring.isObject()) return;

        JsonNode keyNumbersNode = blueprint.path("key_numbers");
        ArrayNode keyNumbers = keyNumbersNode.isArray()
                ? (ArrayNode) keyNumbersNode
                : blueprint.putArray("key_numbers");

        // total_children directly under co_occurring
        JsonNode tc = coOccurring.get("total_children");
        if (tc != null && !tc.isNull()) {
            addKeyNumber(keyNumbers, tc.asText(), "co-occurring total children events",
                    "co_occurring.total_children");
        }

        // during_event.<scorer>.<field> — flatten every leaf under each child scorer.
        // Labels are humanized so the LLM doesn't copy raw field names into prose:
        //   "sum_orders" → "orders", "sum_total_shares" → "total shares", etc.
        //   "post_cancel_cluster" → "post-cancel cluster"
        JsonNode during = coOccurring.path("during_event");
        if (during.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> scorers = during.fields();
            while (scorers.hasNext()) {
                Map.Entry<String, JsonNode> s = scorers.next();
                String scorerId = s.getKey();
                String scorerLabel = humanizeScorerId(scorerId);
                if (!s.getValue().isObject()) continue;
                Iterator<Map.Entry<String, JsonNode>> fields = s.getValue().fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> f = fields.next();
                    String field = f.getKey();
                    JsonNode val = f.getValue();
                    if (val == null || val.isNull() || val.isObject() || val.isArray()) continue;
                    addKeyNumber(keyNumbers,
                            val.asText(),
                            "co-occurring " + scorerLabel + " " + humanizeFieldName(field),
                            "co_occurring.during_event." + scorerId + "." + field);
                }
            }
        }
    }

    /** Replace scorer id underscores with spaces + a hyphen for "post-cancel". */
    private static String humanizeScorerId(final String scorerId) {
        return switch (scorerId) {
            case "post_cancel_cluster" -> "post-cancel cluster";
            case "liquidity_withdrawal" -> "liquidity withdrawal";
            case "large_trade"          -> "large trade";
            default -> scorerId; // halt, iceberg, layering, sweep — already readable
        };
    }

    /**
     * Map raw breakdown field names to human-readable label fragments so
     * the LLM doesn't copy "sum_orders" into prose. The labels become
     * fragments like "co-occurring layering orders" rather than
     * "co-occurring layering sum_orders".
     */
    private static String humanizeFieldName(final String field) {
        return switch (field) {
            case "count"                -> "events";
            case "sum_orders"           -> "orders";
            case "sum_total_shares"     -> "total shares";
            case "sum_distinct_levels"  -> "distinct price levels";
            case "sum_notional_dollars" -> "notional dollars";
            case "sum_deletes"          -> "deletes";
            case "sum_fills"            -> "fills";
            case "sum_executions"       -> "executions";
            // Strip "sum_" prefix on anything else we forgot, leave bare field otherwise
            default -> field.startsWith("sum_") ? field.substring(4).replace('_', ' ')
                                                : field.replace('_', ' ');
        };
    }

    private static void addKeyNumber(final ArrayNode keyNumbers, final String value,
                                     final String label, final String sourceField) {
        ObjectNode entry = keyNumbers.addObject();
        entry.put("value", value);
        entry.put("label", label);
        entry.put("source_field", sourceField);
    }

    /**
     * Best-effort parse. If the model included markdown fences or
     * preamble despite the prompt, try to find the first '{' and last
     * '}' and parse the slice between them.
     */
    private JsonNode parseJson(final String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception primary) {
            // Try to extract a JSON object slice.
            int start = raw.indexOf('{');
            int end   = raw.lastIndexOf('}');
            if (start >= 0 && end > start) {
                String slice = raw.substring(start, end + 1);
                try {
                    return json.readTree(slice);
                } catch (Exception fallback) {
                    throw new RuntimeException(
                            "Blueprint JSON parse failed even after slice. Raw output:\n" + raw, fallback);
                }
            }
            throw new RuntimeException(
                    "Blueprint output had no JSON object braces. Raw:\n" + raw, primary);
        }
    }
}
