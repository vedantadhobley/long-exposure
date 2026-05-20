package com.longexposure.narration;

import com.fasterxml.jackson.databind.JsonNode;
import com.longexposure.llm.LlamaClient;
import com.longexposure.llm.SamplingParams;

/**
 * Pass 2 of the two-pass narration model. Takes the blueprint from
 * {@link BlueprintExtractor} and renders 2-3 sentences of prose, using
 * only the facts the blueprint lists.
 *
 * <p>This is the "style" pass — facts are decided by the extractor; the
 * renderer just chooses words and ordering. The verifier downstream
 * enforces that no new facts appear.
 */
public final class ProseRenderer {

    public static final String PROMPT_VERSION = "render-v4";

    private static final String SYSTEM_PROMPT = """
            You are a financial-data journalist writing for the Long Exposure column —
            a daily report on IEX exchange microstructure activity for a general audience.

            Tone: factual, concise, FT or Bloomberg register. Plain English over jargon when possible.

            Length: as many sentences as the blueprint's facts support — typically 1-3.
            Stop when you've stated every key_number cleanly. Do not pad to reach a target.
            No bullet points, no headers, no preamble.

            STRICT GROUNDING RULE: You will be given a blueprint of facts (key_numbers) about
            one event. Every claim in your prose must trace to a value in key_numbers[] or to
            one of the string fields the blueprint provides (symbol, company_name, exchange).
            Forbidden:
            - Inventing or paraphrasing numbers
            - Comparative or superlative claims ("one of the longest", "the most active")
            - Inferences about activity outside the event ("no other activity was recorded",
              "during an otherwise quiet session")
            - Adjectives describing the session or market state that aren't in the blueprint
              ("volatile", "active", "quiet")
            - Speculating on intent ("appeared to be", "likely a result of")
            - Restating where a security is listed as commentary when the listing exchange
              field is just metadata (use it once as scaffolding, don't analyze it)

            If the blueprint has few facts, write a short narration. A one-sentence narration
            that uses only the blueprint is strictly better than a three-sentence one that
            adds commentary to fill space.

            SUBJECT TICKER RULE: Spell the subject ticker EXACTLY as given in the blueprint.
            Do not add letters, drop letters, or alter casing. If the blueprint provides
            `company_name`, you may use it alongside the ticker (e.g., "Apple Inc. (AAPL)")
            but the ticker spelling must match exactly.

            Style guidance:
            - Use the event subject naturally (e.g., "AMD halted briefly", not "the AMD symbol")
            - Open with the most significant fact, not "On May 8, 2026" — the date is implied
            - Write exchange names and time-zone abbreviations the way a financial journalist
              would (e.g., "on the NYSE", "at 14:00 ET", "the ETF saw…"). These are normal
              English and don't need to be spelled out.
            """;

    private final LlamaClient llama;

    public ProseRenderer(final LlamaClient llama) {
        this.llama = llama;
    }

    public String render(final JsonNode blueprint) {
        String userPrompt =
                "Blueprint (your only source of facts):\n" +
                blueprint.toPrettyString() +
                "\n\nWrite the narration now. Use only the blueprint's facts.";

        // RENDER preset = Qwen3.5 "Instruct mode for general tasks" verbatim
        // (temp=0.7, top_p=0.8, top_k=20, min_p=0, presence_penalty=1.5).
        // Variety in prose is wanted here; verifier catches actual fabrication.
        return llama.chat(SYSTEM_PROMPT, userPrompt, SamplingParams.RENDER).trim();
    }
}
