package com.longexposure.narration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured output from {@link ProseRenderer}. Three semantic slots:
 *
 * <ul>
 *   <li><b>lead</b> — one sentence naming the subject and primary action.
 *       Always present.
 *   <li><b>facts</b> — zero or more supporting sentences, each using a
 *       {@code key_numbers[]} value not already consumed by the lead.
 *   <li><b>coOccurring</b> — one sentence when the breakdown carried a
 *       {@code co_occurring} block; {@code null} otherwise. Required-iff-
 *       present.
 * </ul>
 *
 * <p>The schema is enforced at LLM-call time via OpenAI-style
 * {@code response_format=json_schema}, so what the model returns is
 * guaranteed to parse into this record. The free-form prose seen
 * downstream (in the {@code narratives.narrative} column and in API
 * responses) is the stitched concatenation produced by
 * {@link #stitched()}.
 */
public record RenderResult(String lead, List<String> facts, String coOccurring) {

    public RenderResult {
        if (lead == null || lead.isBlank()) {
            throw new IllegalArgumentException("lead must be non-empty");
        }
        if (facts == null) {
            facts = List.of();
        }
    }

    /**
     * Concatenate slots into a single prose string. Single space between
     * slot values; coOccurring appended only when non-null.
     */
    public String stitched() {
        StringBuilder sb = new StringBuilder(lead.length() + 200);
        sb.append(lead.trim());
        for (String f : facts) {
            if (f == null || f.isBlank()) continue;
            sb.append(' ').append(f.trim());
        }
        if (coOccurring != null && !coOccurring.isBlank()) {
            sb.append(' ').append(coOccurring.trim());
        }
        return sb.toString();
    }

    /**
     * Serialize back to the same JSON shape the LLM emitted. Stored in
     * {@code narratives.render_structured} for downstream consumers.
     */
    public JsonNode toJson(final ObjectMapper json) {
        ObjectNode root = json.createObjectNode();
        root.put("lead", lead);
        ArrayNode arr = root.putArray("facts");
        for (String f : facts) arr.add(f);
        if (coOccurring == null) {
            root.putNull("co_occurring");
        } else {
            root.put("co_occurring", coOccurring);
        }
        return root;
    }

    /** Parse the LLM's JSON response. Trusts the schema-enforced shape. */
    public static RenderResult fromJson(final JsonNode root) {
        String lead = root.path("lead").asText("");
        JsonNode factsNode = root.path("facts");
        List<String> facts = new ArrayList<>();
        if (factsNode.isArray()) {
            for (JsonNode f : factsNode) {
                String s = f.asText("");
                if (!s.isBlank()) facts.add(s);
            }
        }
        JsonNode co = root.get("co_occurring");
        String coOccurring = (co == null || co.isNull()) ? null : co.asText(null);
        if (coOccurring != null && coOccurring.isBlank()) coOccurring = null;
        return new RenderResult(lead, facts, coOccurring);
    }
}
