package com.longexposure.narration;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
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
 * number-shaped token AND every ticker-shaped token, and checks that
 * each one has a basis in the blueprint, the breakdown, or a known
 * ticker.
 *
 * <p>Three layers of grounding:
 * <ol>
 *   <li><b>prose numbers ⊆ blueprint ∪ breakdown</b> — every numeric
 *       token in prose must appear somewhere in either the blueprint
 *       or the original breakdown (as a substring of any value).
 *   <li><b>blueprint key_numbers ⊆ breakdown</b> — every
 *       {@code source_field} reference must be a real key in the
 *       breakdown.
 *   <li><b>prose tickers ⊆ {event symbol} ∪ symbols table</b> — every
 *       all-caps 2-5-letter token in prose must equal the event's own
 *       symbol or be a real ticker in the {@code symbols} reference
 *       table. Catches the ODDTX-from-ODTX class of hallucination.
 * </ol>
 *
 * <p>The ticker check has NO denylist of "non-ticker abbreviations"
 * (ET, ETF, NMS, ...). The prompt is responsible for keeping such
 * abbreviations out of the prose entirely. If the LLM emits one, the
 * verifier flags it as a signal that the prompt needs tightening
 * rather than papering over with a maintained list.
 *
 * <p>Loose matching is intentional for v1 number checks: "5 hours and
 * 34 minutes" in prose corresponds to {@code halt_duration: "5h 34m 4s"}
 * in the breakdown. The verifier extracts numbers (5, 34) and checks
 * substring inclusion in the haystack. Strict semantic matching is a
 * follow-up. False negatives are acceptable as long as actual
 * fabrications get caught.
 */
public final class GroundingVerifier {

    /**
     * Matches numbers including decimals and embedded commas/underscores.
     * Examples matched: 76,120 / 10,638,150.60 / 18.1 / 5
     */
    private static final Pattern NUMBER_RE = Pattern.compile("\\d[\\d,_]*(?:\\.\\d+)?");

    /**
     * Matches all-caps 2-5 letter tokens — the shape of a US ticker
     * symbol. \b on both sides ensures we don't match "ET" inside
     * "PARTNERSHIP" or similar.
     */
    private static final Pattern TICKER_CANDIDATE_RE = Pattern.compile("\\b[A-Z]{2,5}\\b");

    /** Numbers shorter than this are too noisy to verify (single-digit year, etc). */
    private static final int MIN_LENGTH_TO_CHECK = 2;

    /** Numbers we don't care about — common in prose but never narratable claims. */
    private static final Set<String> BORING_NUMBERS = Set.of("2026", "2025");

    /**
     * All real ticker symbols, loaded once per narration-activity run
     * from the {@code symbols} reference table. Empty if the activity
     * couldn't load symbols (e.g. table not yet populated) — in that
     * case the ticker check degrades to "must equal event symbol".
     */
    private final Set<String> validSymbols;

    /**
     * Construct with a known set of legitimate tickers. Use the no-arg
     * constructor when the caller doesn't have access to the symbols
     * table (e.g. {@link VerifierBackfill} running outside the
     * narration activity context).
     */
    public GroundingVerifier(final Set<String> validSymbols) {
        this.validSymbols = validSymbols == null ? Set.of() : validSymbols;
    }

    /** Number-only verifier (skips ticker spell-check). */
    public GroundingVerifier() {
        this(Set.of());
    }

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

        // Layer 2: every number in prose must appear in the haystack — using
        // numeric-equivalence, not just substring match. Both prose numbers
        // and haystack numbers get canonicalized via BigDecimal.stripTrailingZeros,
        // so "431.00" ↔ "431.0" ↔ "431" all collapse to the same key.
        // Substring match is kept as a fallback for non-numeric tokens that
        // happen to look like numbers (e.g. parts of IDs).
        String haystack = haystack(blueprint, breakdown);
        Set<String> haystackCanonical = canonicalNumbersIn(haystack);
        Set<String> proseNumbers = extractNumbers(prose);
        for (String n : proseNumbers) {
            if (n.length() < MIN_LENGTH_TO_CHECK) continue;
            if (BORING_NUMBERS.contains(n)) continue;

            // Try numeric-equivalent match first
            String canonical = canonicalize(n);
            if (canonical != null && haystackCanonical.contains(canonical)) continue;

            // Fallback: substring match on the raw haystack (catches numbers
            // embedded in strings the regex didn't pick up, e.g. inside a
            // larger numeric token).
            if (haystack.contains(n)) continue;
            String stripped = n.replace(",", "").replace("_", "");
            if (stripped.length() >= MIN_LENGTH_TO_CHECK && haystack.contains(stripped)) continue;

            mismatches.add("prose contains number \"" + n + "\" (canonical \"" + canonical
                    + "\") not found in blueprint or breakdown");
        }

        // Layer 3: every ticker-shaped token in prose must match the
        // event's own symbol OR be in the symbols reference table.
        // Skipped entirely when validSymbols is empty (back-compat path)
        // — in that case we ONLY enforce "must equal event symbol".
        String eventSymbol = breakdown.path("symbol").asText("");
        Matcher tm = TICKER_CANDIDATE_RE.matcher(prose);
        Set<String> proseTickerCandidates = new HashSet<>();
        while (tm.find()) proseTickerCandidates.add(tm.group());
        for (String token : proseTickerCandidates) {
            if (token.equals(eventSymbol)) continue;
            if (validSymbols.contains(token)) continue;
            mismatches.add("prose contains ticker-shaped token \"" + token
                    + "\" — not event symbol \"" + eventSymbol
                    + "\" and not in symbols reference table");
        }

        boolean passed = mismatches.isEmpty();
        return new Result(passed, mismatches, proseNumbers.size());
    }

    /** Find every number-token in the haystack and canonicalize each. */
    private static Set<String> canonicalNumbersIn(final String haystack) {
        Set<String> out = new HashSet<>();
        Matcher m = NUMBER_RE.matcher(haystack);
        while (m.find()) {
            String c = canonicalize(m.group());
            if (c != null) out.add(c);
        }
        return out;
    }

    /**
     * Canonical numeric form: strip commas + underscores, parse as
     * BigDecimal, strip trailing zeros, return toPlainString. Returns
     * null if the token isn't parseable as a number.
     *
     * <p>"431.0", "431.00", "431.000" all → "431".
     * "13.60" → "13.6". "76,120" → "76120".
     */
    private static String canonicalize(final String token) {
        String cleaned = token.replace(",", "").replace("_", "");
        try {
            BigDecimal bd = new BigDecimal(cleaned).stripTrailingZeros();
            // Edge case: stripTrailingZeros on "0.0" or "0.00" returns "0E-1"
            // / "0E-2" with non-zero scale — force to "0" for matching.
            if (bd.signum() == 0) return "0";
            return bd.toPlainString();
        } catch (NumberFormatException e) {
            return null;
        }
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
