package com.longexposure.narration;

import com.fasterxml.jackson.databind.JsonNode;
import com.longexposure.llm.LlamaClient;

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

    public static final String PROMPT_VERSION = "render-v1";

    private static final String SYSTEM_PROMPT = """
            You are a financial-data journalist writing for the Long Exposure column —
            a daily report on IEX exchange microstructure activity for a general audience.

            Tone: factual, concise, FT or Bloomberg register. Plain English over jargon when possible.
            Length: 2-3 sentences. No bullet points, no headers, no preamble.

            STRICT GROUNDING RULE: You will be given a blueprint of facts (key_numbers) about
            one event. You may ONLY use the values that appear in key_numbers[].value.
            - Do not invent or paraphrase numbers.
            - Do not introduce facts not in the blueprint.
            - Do not speculate on intent ("appeared to be" / "likely a result of" / etc).
            - Use the subject (symbol) and what_happened (event type) as scaffolding.

            Style guidance:
            - Use the event "subject" naturally (e.g., "AMD", not "the AMD symbol")
            - Open with the most significant fact, not "On May 8, 2026" — the date is implied
            - Refer to "the order book" or "the market" rather than "IEX" repeatedly
            """;

    private final LlamaClient llama;

    public ProseRenderer(final LlamaClient llama) {
        this.llama = llama;
    }

    public String render(final JsonNode blueprint) {
        String userPrompt =
                "Blueprint (your only source of facts):\n" +
                blueprint.toPrettyString() +
                "\n\nWrite the 2-3 sentence narration now.";

        // Slightly higher temperature than extract — we want some prose variety
        // but not creative drift. min-p would be even better; the OpenAI-compat
        // endpoint doesn't expose it via the standard chat schema.
        return llama.chat(SYSTEM_PROMPT, userPrompt, 0.3).trim();
    }
}
