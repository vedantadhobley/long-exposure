package com.longexposure.narration;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-code rubric verifier — the load-bearing correctness mechanism in
 * the narration pipeline. Trusts the LLM to write natural financial
 * prose; checks only the two things that can go genuinely wrong.
 *
 * <p>Four checks:
 * <ol>
 *   <li><b>blueprint key_numbers ⊆ breakdown</b> — every
 *       {@code source_field} reference in the blueprint must be a real
 *       key in the breakdown JSON. Catches Pass-1 (extract) inventing
 *       fields that don't exist.
 *   <li><b>prose numbers ⊆ blueprint ∪ breakdown</b> — every numeric
 *       token in prose must appear somewhere in either the blueprint
 *       or the original breakdown. Catches Pass-2 (render) inventing
 *       or paraphrasing numbers.
 *   <li><b>event symbol present in prose</b> — the breakdown's
 *       {@code symbol} value (when set) must appear literally somewhere
 *       in the prose. Catches the ODDTX-from-ODTX class of
 *       hallucination: if the model fabricates a symbol variant, the
 *       real symbol won't be in prose anywhere.
 *   <li><b>prose company name ⊆ breakdown company_name</b> — when the
 *       prose introduces the subject in "<Words> (TICKER)" form, the
 *       words must token-subset agree with the breakdown's
 *       {@code company_name}. Catches the model substituting a company
 *       name from training memory when its memory disagrees with the
 *       breakdown.
 * </ol>
 *
 * <p><b>Explicitly NOT doing</b>: scanning prose for all-caps tokens
 * that "look like tickers". An earlier version of this verifier did
 * exactly that — false-flagged every legitimate use of "NYSE", "ETF",
 * "ET" (timezone), etc. The LLM is a 122B model writing FT-register
 * financial prose; it correctly uses these abbreviations the way any
 * journalist would. The verifier should not police natural language;
 * it should police the two things the LLM can actually get wrong:
 * fabricating numbers, and mangling the subject ticker.
 *
 * <p>Loose matching is intentional for number checks: "5 hours and
 * 34 minutes" in prose corresponds to {@code halt_duration: "5h 34m 4s"}
 * in the breakdown. The verifier extracts numbers (5, 34) and checks
 * substring inclusion in the haystack. False negatives are acceptable
 * as long as actual fabrications get caught.
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
     * Cardinal word-form numerals → canonical digit-string form. Single-word
     * coverage only; composite forms ("twenty-five", "one hundred and fifty")
     * are intentionally NOT supported because finance prose overwhelmingly
     * uses digit form for any count not in {one..twenty, decade-words}.
     *
     * <p>Why this exists: the verifier's number-extraction regex
     * ({@link #NUMBER_RE}) is digit-only, so prose like "eight events"
     * extracted zero numbers and silently passed verification regardless
     * of whether eight is grounded. Observed 2026-05-28 in 05-12 daily
     * synthesis: "eight TQQQ events" (actually 14), "ten DGP layering"
     * (actually 12), "six QQQ withdrawals" (actually 14), "four IWM
     * post_cancel" (actually 5), "three IWM sweeps" (actually 4) —
     * five wrong counts in one paragraph, all bypassing the verifier.
     */
    private static final Map<String, String> WORD_NUMBERS;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("zero", "0");
        m.put("one", "1"); m.put("two", "2"); m.put("three", "3");
        m.put("four", "4"); m.put("five", "5"); m.put("six", "6");
        m.put("seven", "7"); m.put("eight", "8"); m.put("nine", "9");
        m.put("ten", "10");
        m.put("eleven", "11"); m.put("twelve", "12"); m.put("thirteen", "13");
        m.put("fourteen", "14"); m.put("fifteen", "15"); m.put("sixteen", "16");
        m.put("seventeen", "17"); m.put("eighteen", "18"); m.put("nineteen", "19");
        m.put("twenty", "20"); m.put("thirty", "30"); m.put("forty", "40");
        m.put("fifty", "50"); m.put("sixty", "60"); m.put("seventy", "70");
        m.put("eighty", "80"); m.put("ninety", "90");
        m.put("hundred", "100"); m.put("thousand", "1000");
        m.put("dozen", "12");
        WORD_NUMBERS = Map.copyOf(m);
    }

    private static final Pattern WORD_NUMBER_RE = Pattern.compile(
            "\\b(zero|one|two|three|four|five|six|seven|eight|nine|ten|"
                    + "eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|"
                    + "eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|"
                    + "eighty|ninety|hundred|thousand|dozen)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Composite "{unit} {magnitude}" word-numerals — handles "one hundred",
     * "two thousand", "fifteen hundred". Captured as a SINGLE token so the
     * unit ("one") doesn't get double-counted as a standalone numeral.
     * Composite range covers 1..19 × {hundred, thousand}; tens-with-units
     * like "twenty-five" still aren't supported (rare in finance prose).
     */
    private static final Pattern COMPOSITE_WORD_NUMBER_RE = Pattern.compile(
            "\\b(one|two|three|four|five|six|seven|eight|nine|ten|"
                    + "eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|"
                    + "eighteen|nineteen)\\s+(hundred|thousand)\\b",
            Pattern.CASE_INSENSITIVE);

    public GroundingVerifier() {}

    /**
     * @return result detailing pass/fail + the specific mismatches found.
     */
    public Result verify(final String prose, final JsonNode blueprint, final JsonNode breakdown) {
        List<String> mismatches = new ArrayList<>();

        // Layer 1: every key_number.source_field must exist in breakdown.
        // Supports dotted paths (e.g. "co_occurring.during_event.post_cancel_cluster.sum_total_shares")
        // so enrichment-derived numbers can be cited by their nested location.
        JsonNode keyNumbers = blueprint.path("key_numbers");
        if (keyNumbers.isArray()) {
            for (JsonNode kn : keyNumbers) {
                String src = kn.path("source_field").asText("");
                if (src.isEmpty()) {
                    mismatches.add("blueprint key_number missing source_field: " + kn);
                } else if (!hasPath(breakdown, src)) {
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
        Set<String> haystackWordNums  = cardinalWordNumbersIn(haystack);
        Set<String> proseNumbers      = extractNumbers(prose);
        for (String n : proseNumbers) {
            if (n.length() < MIN_LENGTH_TO_CHECK) continue;
            if (BORING_NUMBERS.contains(n)) continue;

            // Try numeric-equivalent match first
            String canonical = canonicalize(n);
            if (canonical != null && haystackCanonical.contains(canonical)) continue;
            // Word-form in haystack maps to digit form ("three" in breakdown → "3"),
            // so prose "3" should also accept that.
            if (canonical != null && haystackWordNums.contains(canonical)) continue;

            // Fallback: substring match on the raw haystack (catches numbers
            // embedded in strings the regex didn't pick up, e.g. inside a
            // larger numeric token).
            if (haystack.contains(n)) continue;
            String stripped = n.replace(",", "").replace("_", "");
            if (stripped.length() >= MIN_LENGTH_TO_CHECK && haystack.contains(stripped)) continue;

            mismatches.add("prose contains number \"" + n + "\" (canonical \"" + canonical
                    + "\") not found in blueprint or breakdown");
        }

        // Layer 2b: cardinal word-form numerals in prose ("three", "ten") must
        // also ground. No length filter — word-form is unambiguous, and
        // single-digit word-form like "five" is exactly the case we need to
        // catch (DESCRIBE renders breakdown counts in word form, e.g. "three
        // sweep events" from co_occurring.during_event.sweep.count="3").
        Set<String> proseWordNums = cardinalWordNumbersIn(prose);
        for (String wn : proseWordNums) {
            if (haystackCanonical.contains(wn)) continue;
            if (haystackWordNums.contains(wn))  continue;
            mismatches.add("prose cardinal word-form \"" + wn
                    + "\" not found in blueprint or breakdown");
        }

        // Layer 3: the event's own symbol must appear literally in the
        // prose. Catches both the ODDTX-from-ODTX class of fabrication
        // (the LLM mangled the ticker, so the real one is now missing)
        // AND the entirely-wrong-subject class (the LLM narrated about
        // some other symbol). Skipped only when the breakdown has no
        // symbol field — VerifierBackfill running over older rows.
        String eventSymbol = breakdown.path("symbol").asText("");
        if (!eventSymbol.isEmpty() && !prose.contains(eventSymbol)) {
            mismatches.add("event symbol \"" + eventSymbol
                    + "\" not found in prose — possible fabrication or wrong-subject hallucination");
        }

        // Layer 4: if the prose introduces the subject in "<Words> (TICKER)"
        // form (the journalist "Company (TICKER)" pattern), the company-shaped
        // word-run immediately preceding "(TICKER)" must agree with the
        // breakdown's company_name. Window-based exact token match — accepts
        // "Intel Corp." for "Intel Corporation" (entity-type tokens dropped),
        // rejects "Comstock Mining Inc." for "Comstock Inc." (extra word) and
        // "Oculus Dynamics" for "Odyssey Therapeutics" (substitution).
        //
        // Works for both subject-led ("Apple Inc. (AAPL) reported…") and
        // verb-led prose ("Liquidity withdrawal occurred on iShares Russell
        // 2000 Index Fund (IWM) marked by…") by examining only the trailing
        // N-word window where N = word count of breakdown.company_name.
        if (!eventSymbol.isEmpty()) {
            String bdCompany = breakdown.path("company_name").asText("");
            if (!bdCompany.isEmpty()) {
                String proseLeading = extractLeadingBeforeTicker(prose, eventSymbol);
                if (proseLeading != null && !companyNamesAgree(proseLeading, bdCompany)) {
                    mismatches.add("prose company near \"(" + eventSymbol + ")\" "
                            + "does not match breakdown company_name \"" + bdCompany + "\"");
                }
            }
        }

        boolean passed = mismatches.isEmpty();
        return new Result(passed, mismatches, proseNumbers.size() + proseWordNums.size());
    }

    /**
     * Return the text immediately preceding a {@code "(TICKER)"} occurrence,
     * or {@code null} if no parenthetical-ticker pattern is present in the
     * prose at all. When present, the entire leading text is returned —
     * narrowing to the company-name region is the caller's responsibility
     * (see {@link #companyNamesAgree}).
     */
    static String extractLeadingBeforeTicker(final String prose, final String symbol) {
        String needle = "(" + symbol + ")";
        int idx = prose.indexOf(needle);
        if (idx <= 0) return null;            // no parenthetical, or ticker at very start
        return prose.substring(0, idx).trim();
    }

    /**
     * Whether the company name in the prose agrees with the one in the
     * breakdown. Compares significant-token sets on the trailing N-word
     * window of the prose, where N = word count of the breakdown's
     * company_name (with a +1 buffer for entity-type tokens like "Inc.").
     *
     * <p>The window approach handles verb-led prose ("Liquidity withdrawal
     * occurred on iShares Russell 2000 Index Fund (IWM)") without
     * over-capturing — only the words that could plausibly be the company
     * name are examined.
     *
     * <p>Accepts: "Intel Corp." ↔ "Intel Corporation"; "Toast, Inc." ↔
     * "Toast, Inc. Class A Common Stock"; "Accenture Plc" ↔ "Accenture plc".
     * Rejects: "Anterix Inc." ↔ "Antelope Enterprise Holdings";
     * "Comstock Mining Inc." ↔ "Comstock Inc." (extra word);
     * "Oculus Dynamics" ↔ "Odyssey Therapeutics" (substitution).
     */
    static boolean companyNamesAgree(final String proseLeading, final String bdCompany) {
        Set<String> bdTokens = significantTokens(bdCompany);
        if (bdTokens.isEmpty()) return true;    // only structural tokens — nothing to verify

        // Try a range of trailing-window sizes from bdWordCount+2 down to
        // bdWordCount-1. The +2 upper bound allows the prose to add entity-
        // type tokens like "Inc."; the -1 lower bound handles cases where
        // the prose abbreviates ("Intel Corp." for "Intel Corporation").
        // Accept if ANY window size produces an exact token-set match.
        //
        // This handles both subject-led prose ("Apple Inc. (AAPL)…") and
        // verb-led prose ("Liquidity withdrawal occurred on iShares Russell
        // 2000 Index Fund (IWM)…") — for the latter, the smallest window
        // that excludes the verb-glue ("occurred on") is the company-name
        // window.
        String[] proseWords = proseLeading.trim().split("\\s+");
        int bdWordCount = bdCompany.trim().split("\\s+").length;
        int maxN = Math.min(proseWords.length, bdWordCount + 2);
        int minN = Math.max(1, bdWordCount - 1);
        for (int n = maxN; n >= minN; n--) {
            int startIdx = Math.max(0, proseWords.length - n);
            StringBuilder tail = new StringBuilder();
            for (int i = startIdx; i < proseWords.length; i++) {
                if (tail.length() > 0) tail.append(' ');
                tail.append(proseWords[i]);
            }
            Set<String> proseTokens = significantTokens(tail.toString());
            // Exact set equality — both containment directions:
            //   bd.containsAll(prose) rejects ADDED words ("Mining" in "Comstock Mining Inc.")
            //   prose.containsAll(bd) rejects SUBSTITUTIONS ("Oculus" instead of "Odyssey")
            if (bdTokens.equals(proseTokens)) return true;
        }
        return false;
    }

    /** Tokens that are entity-type / filing-decoration noise (vs identity). */
    private static final Set<String> STRUCTURAL_TOKENS = Set.of(
            "inc", "incorporated", "corp", "corporation",
            "co", "company", "companies",
            "ltd", "limited", "plc", "llc", "lp", "lllp", "nv", "ag", "sa",
            "the", "and", "of",
            "common", "stock", "class", "series", "ordinary", "shares",
            "trust", "fund", "etf", "etn", "adr"
    );

    private static Set<String> significantTokens(final String name) {
        Set<String> out = new HashSet<>();
        if (name == null || name.isBlank()) return out;
        for (String raw : name.toLowerCase().split("[\\s.,&()/'\\-]+")) {
            String t = raw.replaceAll("[^a-z0-9]", "");
            if (t.length() < 2) continue;
            if (STRUCTURAL_TOKENS.contains(t)) continue;
            out.add(t);
        }
        return out;
    }

    /**
     * Resolve a dotted path against a JSON object. Returns true iff every
     * segment exists and the leaf is a non-null value.
     *
     * <p>Examples (with the breakdown JSON above an enriched
     * liquidity_withdrawal):
     * <ul>
     *   <li>{@code "deletes"} → true (top-level key)
     *   <li>{@code "co_occurring.during_event.post_cancel_cluster.sum_total_shares"} → true
     *   <li>{@code "co_occurring.during_event.iceberg.count"} → false (iceberg subkey absent)
     * </ul>
     */
    static boolean hasPath(final JsonNode root, final String dotted) {
        if (root == null || root.isNull() || dotted == null || dotted.isEmpty()) return false;
        JsonNode cur = root;
        for (String seg : dotted.split("\\.")) {
            if (cur == null || cur.isNull() || !cur.isObject()) return false;
            if (!cur.has(seg)) return false;
            cur = cur.get(seg);
        }
        return cur != null && !cur.isNull();
    }

    /** Find every number-token in the haystack and canonicalize each. */
    static Set<String> canonicalNumbersIn(final String haystack) {
        Set<String> out = new HashSet<>();
        Matcher m = NUMBER_RE.matcher(haystack);
        while (m.find()) {
            String c = canonicalize(m.group());
            if (c != null) out.add(c);
        }
        return out;
    }

    /**
     * Extract every cardinal word-form numeral ("five", "ten", "thirty") from
     * the text and return each in canonical digit-string form ("5", "10",
     * "30"). Case-insensitive. Single-word coverage only.
     *
     * <p>Separate from {@link #canonicalNumbersIn} (which is digit-only) so
     * verifiers can apply different filtering to each — word-form numerals
     * always get checked (no length-based skip), digit-form numerals keep
     * the {@code length < MIN_LENGTH_TO_CHECK} filter to ignore spurious
     * single-digit matches.
     */
    public static Set<String> cardinalWordNumbersIn(final String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;

        // Pre-pass: composite "{unit} {hundred|thousand}". Compute the product,
        // add to the result, and mask the matched span so the inner unit word
        // isn't re-extracted as a standalone numeral in the next pass.
        StringBuilder masked = new StringBuilder(text);
        Matcher cm = COMPOSITE_WORD_NUMBER_RE.matcher(text);
        while (cm.find()) {
            String unit = WORD_NUMBERS.get(cm.group(1).toLowerCase());
            String mag  = WORD_NUMBERS.get(cm.group(2).toLowerCase());
            if (unit != null && mag != null) {
                int product = Integer.parseInt(unit) * Integer.parseInt(mag);
                out.add(String.valueOf(product));
                for (int i = cm.start(); i < cm.end(); i++) masked.setCharAt(i, ' ');
            }
        }

        // Standalone pass on the masked text.
        Matcher m = WORD_NUMBER_RE.matcher(masked.toString());
        while (m.find()) {
            String wn = WORD_NUMBERS.get(m.group().toLowerCase());
            if (wn != null) out.add(wn);
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
    static String canonicalize(final String token) {
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

    public static void appendAllValues(final JsonNode node, final StringBuilder sb) {
        if (node == null || node.isNull()) return;
        if (node.isValueNode()) {
            // Numeric JSON nodes need special handling: Jackson's asText()
            // delegates to Double.toString() for floating-point values,
            // which produces scientific notation for magnitudes ≥ 1e7
            // ("10694140.05" → "1.069414005E7"). NUMBER_RE then misses
            // the value and the verifier falsely rejects matching prose.
            // Use BigDecimal.toPlainString() to force the readable form.
            if (node.isNumber()) {
                sb.append(node.decimalValue().toPlainString()).append(' ');
            } else {
                sb.append(node.asText()).append(' ');
            }
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
