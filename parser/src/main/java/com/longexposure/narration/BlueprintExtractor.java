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
    public static final String PROMPT_VERSION = "extract-v1";

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
        //      subject. Sourced from the breakdown (which Enrich already
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
            addKeyNumber(keyNumbers, tc.asText(), "co-occurring total children",
                    "co_occurring.total_children");
        }

        // during_event.<scorer>.<field> — flatten every leaf under each child scorer
        JsonNode during = coOccurring.path("during_event");
        if (during.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> scorers = during.fields();
            while (scorers.hasNext()) {
                Map.Entry<String, JsonNode> s = scorers.next();
                String scorerId = s.getKey();
                if (!s.getValue().isObject()) continue;
                Iterator<Map.Entry<String, JsonNode>> fields = s.getValue().fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> f = fields.next();
                    String field = f.getKey();
                    JsonNode val = f.getValue();
                    if (val == null || val.isNull() || val.isObject() || val.isArray()) continue;
                    addKeyNumber(keyNumbers,
                            val.asText(),
                            "co-occurring " + scorerId + " " + field,
                            "co_occurring.during_event." + scorerId + "." + field);
                }
            }
        }
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
