package com.longexposure.narration;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-code rubric verifier — the load-bearing correctness mechanism in
 * the narration pipeline. Walks the rendered prose, extracts every
 * number-shaped token, and checks that each one has a basis in either
 * the blueprint's {@code key_numbers[]} or the original breakdown.
 *
 * <p>Two layers of grounding:
 * <ol>
 *   <li><b>prose ⊆ blueprint ∪ breakdown</b> — every number in prose must
 *       appear somewhere in either the blueprint or the original breakdown
 *       (as a substring of any value).
 *   <li><b>blueprint ⊆ breakdown</b> — every key_number's source_field
 *       must be a real key in the breakdown.
 * </ol>
 *
 * <p>Loose matching is intentional for v1: "5 hours and 34 minutes" in
 * prose corresponds to {@code halt_duration: "5h 34m 4s"} in the breakdown.
 * The verifier extracts numbers (5, 34) and checks substring inclusion in
 * the haystack. Strict semantic matching is a follow-up. False negatives
 * are acceptable as long as actual fabrications get caught.
 */
public final class GroundingVerifier {

    /**
     * Matches numbers including decimals and embedded commas/underscores.
     * Examples matched: 76,120 / 10,638,150.60 / 18.1 / 5
     */
    private static final Pattern NUMBER_RE = Pattern.compile("\\d[\\d,_]*(?:\\.\\d+)?");

    /** Numbers shorter than this are too noisy to verify (single-digit year, etc). */
    private static final int MIN_LENGTH_TO_CHECK = 2;

    /** Numbers we don't care about — common in prose but never narratable claims. */
    private static final Set<String> BORING_NUMBERS = Set.of("2026", "2025");

    /**
     * @return result detailing pass/fail + the specific mismatches found.
     */
    public Result verify(final String prose, final JsonNode blueprint, final JsonNode breakdown) {
        List<String> mismatches = new ArrayList<>();

        // Layer 1: every key_number.source_field must exist in breakdown
        JsonNode keyNumbers = blueprint.path("key_numbers");
        if (keyNumbers.isArray()) {
            for (JsonNode kn : keyNumbers) {
                String src = kn.path("source_field").asText("");
                if (src.isEmpty()) {
                    mismatches.add("blueprint key_number missing source_field: " + kn);
                } else if (!breakdown.has(src)) {
                    mismatches.add("blueprint key_number source_field=\"" + src + "\" not present in breakdown");
                }
            }
        }

        // Layer 2: every number in prose must appear somewhere in the haystack
        // (blueprint values + breakdown values as a single big string).
        String haystack = haystack(blueprint, breakdown);
        Set<String> proseNumbers = extractNumbers(prose);
        for (String n : proseNumbers) {
            if (n.length() < MIN_LENGTH_TO_CHECK) continue;
            if (BORING_NUMBERS.contains(n)) continue;
            // Substring match (handles "76,120" appearing in haystack)
            if (haystack.contains(n)) continue;
            // Also try the comma-stripped version ("76120" might appear)
            String stripped = n.replace(",", "").replace("_", "");
            if (stripped.length() >= MIN_LENGTH_TO_CHECK && haystack.contains(stripped)) continue;
            // Try the integer floor (for cases where prose rounded "139.755" → "139.76")
            // We don't auto-resolve this for v1; just flag it.
            mismatches.add("prose contains number \"" + n + "\" not found in blueprint or breakdown");
        }

        boolean passed = mismatches.isEmpty();
        return new Result(passed, mismatches, proseNumbers.size());
    }

    private static String haystack(final JsonNode blueprint, final JsonNode breakdown) {
        StringBuilder sb = new StringBuilder(1024);
        appendAllValues(blueprint, sb);
        sb.append('\n');
        appendAllValues(breakdown, sb);
        return sb.toString();
    }

    private static void appendAllValues(final JsonNode node, final StringBuilder sb) {
        if (node == null || node.isNull()) return;
        if (node.isValueNode()) {
            sb.append(node.asText()).append(' ');
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) appendAllValues(child, sb);
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                appendAllValues(e.getValue(), sb);
            }
        }
    }

    private static Set<String> extractNumbers(final String text) {
        Set<String> out = new HashSet<>();
        Matcher m = NUMBER_RE.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    /**
     * Result of a verification pass.
     *
     * @param passed         true if no mismatches found
     * @param mismatches     human-readable list of grounding issues
     * @param numbersChecked count of distinct number tokens found in prose
     */
    public record Result(boolean passed, List<String> mismatches, int numbersChecked) {}
}
