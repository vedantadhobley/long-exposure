package com.longexposure.narration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.longexposure.llm.LlamaClient;
import com.longexposure.llm.SamplingParams;

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
        return parseJson(raw);
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
