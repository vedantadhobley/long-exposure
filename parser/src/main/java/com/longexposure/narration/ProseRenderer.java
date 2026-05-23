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

    public static final String PROMPT_VERSION = "render-v9-holistic";

    private static final String SYSTEM_PROMPT = """
            You are a financial-data journalist writing for the Long Exposure column —
            a daily report on IEX exchange microstructure activity for a general audience.

            OUTPUT: a JSON object with three fields — `lead`, `facts`, `co_occurring`.

              - lead: one sentence. Names the subject and describes what happened,
                using at least one value from `key_numbers[]`. When the blueprint
                provides `company_name`, write the subject as
                "<company_name> (<symbol>)"; otherwise use the symbol alone.

              - facts: an array of sentences (zero or more). Each uses at least one
                `key_numbers[].value` not already consumed by the lead. Together with
                the lead, the array surfaces every key_numbers entry.

              - co_occurring: one sentence describing the breakdown's `co_occurring`
                block (nested events that happened inside the parent event's window),
                or null when no such block is present. Any `key_numbers[]` entry whose
                `source_field` begins with `"co_occurring."` belongs in this slot —
                not in `facts[]`.

            GROUNDING — the primary rule:

            The blueprint is your only source of truth. Use only values from
            `key_numbers[]`, the blueprint's `symbol` and `company_name` strings, and
            values from the breakdown's `co_occurring` block when referenced. Numbers
            appear in your output exactly as they appear in the blueprint — no
            rounding, paraphrasing, or approximating. Do not infer, interpret,
            compare to other events, or assert post-event state. Do not restate
            facts a previous sentence already conveyed.

            REGISTER:

            Past tense — describe what happened during the event window. FT or
            Bloomberg register: factual, concise, plain English over jargon. Use
            exchange and timezone abbreviations naturally ("on the NYSE",
            "at 14:00 ET"). Open with the most significant fact; the date is implied.
            """;

    /**
     * Two pre-built schema variants. The schema is selected per-call
     * based on whether the breakdown contains a co_occurring block:
     *
     * <ul>
     *   <li>{@link #schemaWithCoOccurring} — co_occurring is a
     *       required non-null string. The model MUST emit a sentence
     *       there; it cannot dump the data into facts[] and leave the
     *       slot null.
     *   <li>{@link #schemaWithoutCoOccurring} — co_occurring is
     *       required to be null. There's no enrichment data to surface,
     *       so the slot must be null.
     * </ul>
     *
     * <p>Branching the schema per-call (rather than using a single
     * nullable schema) is what enforces the semantic separation —
     * before this, the schema allowed null even when data was present,
     * and the model always chose null and stuffed the co_occurring
     * data into facts[] instead.
     */
    private final JsonNode schemaWithCoOccurring;
    private final JsonNode schemaWithoutCoOccurring;
    private final LlamaClient llama;
    private final ObjectMapper json;

    public ProseRenderer(final LlamaClient llama) {
        this(llama, new ObjectMapper());
    }

    public ProseRenderer(final LlamaClient llama, final ObjectMapper json) {
        this.llama  = llama;
        this.json   = json;
        this.schemaWithCoOccurring    = buildSchema(json, /*coOccurringNullable=*/ false);
        this.schemaWithoutCoOccurring = buildSchema(json, /*coOccurringNullable=*/ true);
    }

    public RenderResult render(final JsonNode blueprint) {
        boolean hasCoOccurring = hasCoOccurringBlock(blueprint);
        JsonNode schema = hasCoOccurring ? schemaWithCoOccurring : schemaWithoutCoOccurring;
        String userPrompt =
                "Blueprint (your only source of facts):\n" +
                blueprint.toPrettyString() +
                "\n\n" +
                (hasCoOccurring
                        ? "The blueprint's breakdown contains co_occurring data. The schema "
                          + "REQUIRES the co_occurring slot to be a non-null sentence — "
                          + "it cannot be null. Reference at least one number from the "
                          + "co_occurring block in that sentence."
                        : "No co_occurring block in the breakdown — the schema requires "
                          + "co_occurring to be null.") +
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

    /**
     * Detects whether this blueprint carries co_occurring data by
     * inspecting {@code key_numbers[].source_field} for paths beginning
     * with {@code "co_occurring."}. The extractor populates source_field
     * with the dotted path through the breakdown, so any number sourced
     * from the breakdown's co_occurring block has a source_field like
     * {@code "co_occurring.during_event.post_cancel_cluster.sum_orders"}.
     *
     * <p>This is the load-bearing detection that decides which schema
     * variant to send (co_occurring required vs co_occurring null).
     */
    static boolean hasCoOccurringBlock(final JsonNode blueprint) {
        JsonNode keyNumbers = blueprint.path("key_numbers");
        if (!keyNumbers.isArray()) return false;
        for (JsonNode kn : keyNumbers) {
            String src = kn.path("source_field").asText("");
            if (src.startsWith("co_occurring.") || src.equals("co_occurring")) {
                return true;
            }
        }
        return false;
    }

    /**
     * JSON Schema for the render output. Top-level object with three
     * required keys.
     *
     * @param coOccurringNullable when true, co_occurring is {@code null}
     *                            (the breakdown has no co_occurring block,
     *                            so the slot must be empty). When false,
     *                            co_occurring must be a non-null string
     *                            with {@code minLength=1} (the breakdown
     *                            has data the model must surface).
     */
    private static JsonNode buildSchema(final ObjectMapper json, final boolean coOccurringNullable) {
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
        if (coOccurringNullable) {
            // No co_occurring data in the breakdown — must be null.
            coOccurring.put("type", "null");
        } else {
            // Breakdown has co_occurring data — must be a non-empty sentence.
            coOccurring.put("type", "string");
            coOccurring.put("minLength", 1);
        }

        root.putArray("required").add("lead").add("facts").add("co_occurring");
        return root;
    }
}
