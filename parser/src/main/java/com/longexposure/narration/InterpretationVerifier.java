package com.longexposure.narration;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure-code grounding verifier for INTERPRET output. Mirrors the
 * {@link GroundingVerifier} discipline used by DESCRIBE, but with a
 * different input shape and expanded haystack:
 *
 * <ul>
 *   <li><b>No blueprint check</b> — INTERPRET is a single LLM call, not
 *       a 2-pass extract/render flow. There's no blueprint to validate
 *       against; the breakdown + window summaries are the only sources
 *       of truth.
 *   <li><b>Expanded number haystack</b> — prose numbers must appear in
 *       breakdown OR pre-window summary OR post-window summary. The
 *       canonical example: "the post window saw 408 trades for 52,959
 *       shares" — "408" and "52,959" should resolve to the post-window
 *       summary, not the breakdown.
 *   <li><b>Symbol presence</b> — same rule as DESCRIBE. The event's
 *       symbol must appear in prose.
 *   <li><b>Company-name agreement</b> — same rule as DESCRIBE. Reuses
 *       {@link GroundingVerifier#extractLeadingBeforeTicker} +
 *       {@link GroundingVerifier#companyNamesAgree}.
 * </ul>
 *
 * <p>Loose number matching is identical to DESCRIBE's verifier:
 * {@link GroundingVerifier#canonicalize} canonicalizes both prose
 * tokens and haystack tokens through BigDecimal.stripTrailingZeros so
 * "$10.64", "$10.640", and "$10.64M" all match the same way against a
 * breakdown value of 10.64.
 */
public final class InterpretationVerifier {

    /**
     * Intent-claim denylist (same regex family as {@link SynthesisVerifier}). The
     * INTERPRET prompt already forbids intent attribution ("the trader was
     * X-ing", "this was spoofing"), but the verifier didn't previously check —
     * a model leak would have passed. Empirically clean on 2026-05-22 (0/160
     * leaks), but defense-in-depth is cheap and consistent across the three
     * verifier tiers (DESCRIBE has no intent leaks observed either; that tier
     * would add the same check if it ever did).
     */
    private static final java.util.regex.Pattern INTENT_WORDS = java.util.regex.Pattern.compile(
            "\\b(?:manipul\\w*|spoof\\w*|front[- ]?run\\w*|wash[- ]?trad\\w*|"
                    + "gam(?:ing|ed)|illegal\\w*|fake\\w*)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    public InterpretationVerifier() {}

    /**
     * Verify an INTERPRET output against its grounding sources.
     *
     * @param prose         the LLM's interpretation prose (1-2 sentences)
     * @param breakdown     the scorer's breakdown JSON for the event
     * @param preSummary    JSON form of the pre-event ±60-sec window summary
     * @param postSummary   JSON form of the post-event ±60-sec window summary
     * @param eventSymbol   the event's symbol (from {@code breakdown.symbol})
     */
    public Result verify(final String prose,
                         final JsonNode breakdown,
                         final JsonNode preSummary,
                         final JsonNode postSummary,
                         final String eventSymbol) {
        List<String> mismatches = new ArrayList<>();

        // Number check: every numeric token in prose must canonicalize
        // to something present in the breakdown OR pre-window OR
        // post-window summary.
        StringBuilder haystackBuf = new StringBuilder(2048);
        GroundingVerifier.appendAllValues(breakdown,  haystackBuf); haystackBuf.append('\n');
        GroundingVerifier.appendAllValues(preSummary, haystackBuf); haystackBuf.append('\n');
        GroundingVerifier.appendAllValues(postSummary, haystackBuf);
        Set<String> haystackNums = GroundingVerifier.canonicalNumbersIn(haystackBuf.toString());

        // Pre-compute precision-rounded forms of every haystack number at
        // 0..4 decimal places. This lets the verifier accept the LLM's
        // natural rounding choices ("$19.69" for a VWAP of 19.6883) as
        // long as the rounded value actually matches data at that
        // precision. Bounded to ≤ 4 decimals since finance prose never
        // exceeds that.
        Set<String> haystackRoundedForms = precisionRoundedForms(haystackNums);

        Set<String> proseNums = GroundingVerifier.canonicalNumbersIn(prose);
        int numbersChecked = proseNums.size();
        for (String n : proseNums) {
            if (n.length() < 2) continue;                                // single-digit noise
            if (n.equals("2026") || n.equals("2025")) continue;          // year tokens
            if (haystackNums.contains(n))         continue;              // direct match
            if (haystackRoundedForms.contains(n)) continue;              // rounded-form match

            mismatches.add("prose contains number \"" + n
                    + "\" not found in breakdown or surrounding-window summaries");
        }

        // Symbol presence: the event's symbol must appear literally in prose.
        if (eventSymbol != null && !eventSymbol.isEmpty() && !prose.contains(eventSymbol)) {
            mismatches.add("event symbol \"" + eventSymbol
                    + "\" not found in prose — possible fabrication or wrong-subject hallucination");
        }

        // Company-name agreement: when the prose introduces the subject in
        // "<Words> (TICKER)" form, the trailing word-window before
        // "(TICKER)" must token-subset agree with breakdown.company_name.
        // INTERPRET is more likely than DESCRIBE to omit the parenthetical
        // (it's typically a follow-up sentence to a prior description), so
        // missing parenthetical is NOT a failure — only mismatch is.
        if (eventSymbol != null && !eventSymbol.isEmpty()) {
            String bdCompany = breakdown.path("company_name").asText("");
            if (!bdCompany.isEmpty()) {
                String proseLeading = GroundingVerifier.extractLeadingBeforeTicker(prose, eventSymbol);
                if (proseLeading != null) {
                    // Carve-out: when the symbol is also a common English word
                    // (NOW, ETN, ET, ALL, etc.) the LLM may write "on NOW (NOW)"
                    // — bare-ticker reference followed by the parenthetical
                    // form. The Layer-4 check then sees "NOW" as the company
                    // leading words and (correctly) fails it against the
                    // breakdown's "ServiceNow, Inc." That's a stutter pattern,
                    // not a fabrication. Skip the check when the last word(s)
                    // before "(TICKER)" is just the ticker itself.
                    String trailingWord = lastWordOf(proseLeading);
                    boolean isStutter = eventSymbol.equalsIgnoreCase(trailingWord);

                    if (!isStutter && !GroundingVerifier.companyNamesAgree(proseLeading, bdCompany)) {
                        mismatches.add("prose company near \"(" + eventSymbol + ")\" "
                                + "does not match breakdown company_name \"" + bdCompany + "\"");
                    }
                }
            }
        }

        // ─── Intent-claim denylist (same path as SynthesisVerifier) ─────────
        java.util.regex.Matcher dm = INTENT_WORDS.matcher(prose);
        java.util.HashSet<String> intentHits = new java.util.HashSet<>();
        while (dm.find()) intentHits.add(dm.group().toLowerCase());
        for (String hit : intentHits) {
            mismatches.add("prose contains intent-claim word \"" + hit
                    + "\" — pattern-catalog rule: describe shape, not intent");
        }

        return new Result(mismatches.isEmpty(), mismatches, numbersChecked);
    }

    /**
     * Produce the set of {@code round(H, d)} for every haystack number
     * H and every precision d in 1..4, in canonical (stripped-trailing-
     * zeros) form. Skips d=0 to avoid the over-loose case where prose
     * "5" would match a haystack value of 5.34.
     *
     * <p>Why this exists: the LLM naturally rounds VWAPs and ratios to
     * 2 decimal places (FT / Bloomberg convention). The breakdown
     * carries the unrounded raw value (e.g. 19.6883). Pre-computing
     * rounded forms in the haystack lets the verifier match the
     * LLM's journalist-register output while staying strictly
     * grounded — the rounded value still corresponds to real data.
     */
    private static Set<String> precisionRoundedForms(final Set<String> haystackNums) {
        Set<String> out = new HashSet<>();
        for (String n : haystackNums) {
            BigDecimal bd;
            try {
                bd = new BigDecimal(n);
            } catch (NumberFormatException e) {
                continue;
            }
            // d=0 (integer rounding) only when |bd| ≥ 10 — for smaller values
            // integer rounding is too lossy (prose "5" should not match haystack
            // 5.34). For values ≥ 10, integer rounding is journalist-standard
            // ("4,102 seconds" for a 4101.6-sec duration).
            //
            // Use three rounding modes at d=0 to cover LLM rounding ambiguity:
            // some models round 146.5 → 147 (HALF_UP), others → 146 (FLOOR),
            // and the strict ceiling is occasionally used too. The cost of
            // accepting all three is one extra integer per haystack number;
            // the risk is negligible because the integer adjacency (146 vs
            // 147) still corresponds to real data within ±0.5 units.
            if (bd.abs().compareTo(BigDecimal.TEN) >= 0) {
                addRounded(out, bd, 0, RoundingMode.HALF_UP);
                addRounded(out, bd, 0, RoundingMode.FLOOR);
                addRounded(out, bd, 0, RoundingMode.CEILING);
            }
            // At non-zero precision, include both HALF_UP and FLOOR. The
            // model commonly truncates rather than rounds half-up (72.7957
            // becomes "72.79", not "72.80"). FLOOR + HALF_UP covers both
            // conventions. Ceiling at non-zero precision is rare in
            // journalist prose, omitted.
            for (int d = 1; d <= 4; d++) {
                addRounded(out, bd, d, RoundingMode.HALF_UP);
                addRounded(out, bd, d, RoundingMode.FLOOR);
            }
        }
        return out;
    }

    /** Last whitespace-separated word, stripping trailing punctuation. */
    private static String lastWordOf(final String s) {
        if (s == null) return "";
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return "";
        int sp = trimmed.lastIndexOf(' ');
        String last = (sp < 0) ? trimmed : trimmed.substring(sp + 1);
        return last.replaceAll("[,.;:!?]+$", "");
    }

    private static void addRounded(final Set<String> out,
                                    final BigDecimal bd,
                                    final int scale,
                                    final RoundingMode mode) {
        BigDecimal r = bd.setScale(scale, mode).stripTrailingZeros();
        if (r.signum() == 0) out.add("0");
        else out.add(r.toPlainString());
    }

    /**
     * Result of an INTERPRET verification pass.
     *
     * @param passed         true if no mismatches found
     * @param mismatches     human-readable list of grounding issues
     * @param numbersChecked count of distinct number tokens found in prose
     */
    public record Result(boolean passed, List<String> mismatches, int numbersChecked) {}
}
