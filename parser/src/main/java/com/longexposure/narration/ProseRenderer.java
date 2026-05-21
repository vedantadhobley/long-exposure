package com.longexposure.narration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.longexposure.llm.LlamaClient;
import com.longexposure.llm.SamplingParams;

/**
 * Pass 2 of the two-pass narration model. Takes the blueprint from
 * {@link BlueprintExtractor} and emits a {@link RenderResult} —
 * three semantic slots that stitch into the final prose.
 *
 * <p><b>Why slots instead of free prose:</b> earlier free-prose
 * iterations (v3–v5 of the prompt) repeatedly leaked qualitative filler
 * because the model had unconstrained "freestyle space" to fill when
 * the breakdown was thin. The structured output removes that space by
 * construction — every output token lives in a named slot whose
 * semantics the prompt + JSON Schema enforce together.
 *
 * <p>Schema is enforced at the sampler via
 * {@code response_format=json_schema}, so output is guaranteed
 * structurally valid. The verifier downstream still checks number-
 * grounding against the blueprint.
 */
public final class ProseRenderer {

    public static final String PROMPT_VERSION = "render-v6-structured";

    private static final String SYSTEM_PROMPT = """
            You are a financial-data journalist writing for the Long Exposure column —
            a daily report on IEX exchange microstructure activity for a general audience.

            Tone: factual, concise, FT or Bloomberg register. Plain English over jargon when possible.

            You output a single JSON object with three fields: `lead`, `facts`, `co_occurring`.

            FIELD SEMANTICS:

            - lead: one sentence. Names the subject (use the `symbol` from the blueprint,
              optionally with `company_name` formatted as "Company Inc. (TICKER)"). Describes
              what happened. Uses at least one value from key_numbers[].

            - facts: an array of sentences (zero or more). Each sentence uses at least one
              key_numbers[].value not already consumed by the lead. Together with the lead,
              the array should surface every key_numbers entry.

            - co_occurring: one sentence describing the breakdown's `co_occurring` block,
              if present. Frame as "During this interval, X also occurred" or similar.
              These are nested events that happened *inside* the parent event's window —
              part of the parent's story, not separate phenomena. Set to null if the
              breakdown contains no `co_occurring` block.

            GROUNDING (applies to every slot):

            - Numbers must come from key_numbers[].value or from the breakdown's
              `co_occurring` block. No inventing, paraphrasing, or rounding.
            - The `symbol` field is always a ticker, even when it spells a real English
              word (ETN, ET, GE, FOR, AT). Spell it exactly as given.
            - No comparative or superlative claims, no intent speculation, no current-
              or post-event state assertions, no session-state adjectives.

            STYLE:

            - Use exchange and timezone abbreviations naturally ("on the NYSE", "at 14:00 ET").
            - Open with the most significant fact; the date is implied.
            - Speak about the event in past tense — describe what happened during the
              event window, not what came after.
            """;

    /**
     * JSON Schema sent to llama.cpp via {@code response_format} so the
     * sampler is constrained to a parseable shape. Built once + reused.
     */
    private final JsonNode schema;
    private final LlamaClient llama;
    private final ObjectMapper json;

    public ProseRenderer(final LlamaClient llama) {
        this(llama, new ObjectMapper());
    }

    public ProseRenderer(final LlamaClient llama, final ObjectMapper json) {
        this.llama  = llama;
        this.json   = json;
        this.schema = buildSchema(json);
    }

    public RenderResult render(final JsonNode blueprint) {
        boolean hasCoOccurring = hasCoOccurringBlock(blueprint);
        String userPrompt =
                "Blueprint (your only source of facts):\n" +
                blueprint.toPrettyString() +
                "\n\n" +
                (hasCoOccurring
                        ? "The blueprint's breakdown contains co_occurring data — the "
                          + "co_occurring slot is REQUIRED and must reference at least "
                          + "one number from that block."
                        : "No co_occurring block in the breakdown — set co_occurring to null.") +
                "\n\nEmit the JSON object now.";

        // RENDER preset = Qwen3.5 "Instruct mode for general tasks" verbatim
        // (temp=0.7, top_p=0.8, top_k=20, min_p=0, presence_penalty=1.5).
        // Schema enforced at sampler — what comes back is guaranteed-parseable JSON.
        String raw = llama.chat(SYSTEM_PROMPT, userPrompt, SamplingParams.RENDER, schema);
        try {
            return RenderResult.fromJson(json.readTree(raw));
        } catch (Exception e) {
            // Schema-enforcement should make this unreachable, but if it
            // ever fires we want loud failure not silent fallback.
            throw new RuntimeException("render result failed to parse: " + raw, e);
        }
    }

    private static boolean hasCoOccurringBlock(final JsonNode blueprint) {
        // Look in two places: the blueprint's own top-level co_occurring (if extractor
        // surfaced it) and the embedded breakdown reference, since extractor prompts
        // vary in whether they hoist nested fields.
        JsonNode bd = blueprint.path("breakdown");
        JsonNode co = bd.path("co_occurring");
        if (!co.isMissingNode() && !co.isNull()) return true;
        co = blueprint.path("co_occurring");
        return !co.isMissingNode() && !co.isNull();
    }

    /**
     * JSON Schema for the render output. Top-level object with three
     * required keys; {@code co_occurring} is nullable so the LLM can
     * signal "no enrichment" cleanly.
     */
    private static JsonNode buildSchema(final ObjectMapper json) {
        ObjectNode root = json.createObjectNode();
        root.put("type", "object");
        root.put("additionalProperties", false);
        ObjectNode props = root.putObject("properties");

        ObjectNode lead = props.putObject("lead");
        lead.put("type", "string");
        lead.put("minLength", 1);

        ObjectNode facts = props.putObject("facts");
        facts.put("type", "array");
        ObjectNode factsItems = facts.putObject("items");
        factsItems.put("type", "string");
        factsItems.put("minLength", 1);

        ObjectNode coOccurring = props.putObject("co_occurring");
        coOccurring.putArray("type").add("string").add("null");

        root.putArray("required").add("lead").add("facts").add("co_occurring");
        return root;
    }
}
