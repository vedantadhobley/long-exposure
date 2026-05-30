package com.longexposure.narration;

/**
 * Detects when LLM prose contains non-English / non-journalism characters
 * that shouldn't appear in financial-journalist register. Used as the
 * fail-fast first check in every verifier — a non-English token leak
 * (Chinese, Cyrillic, etc.) is rejected so the activity's retry loop
 * re-rolls with sampling variance.
 *
 * <p>Observed failure mode: Qwen3.5-122B-A10B is multilingual and under
 * RENDER preset (temp=0.7, top_p=0.8, top_k=20) occasionally emits a
 * non-Latin token mid-clause — the SPY narrative on 2026-05-22 ended
 * with "9 price水平和" (Chinese fragment). Retry-1 with
 * a different sampling roll virtually never reproduces the same artifact.
 *
 * <p>What's ALLOWED:
 * <ul>
 *   <li>ASCII printable (\x20-\x7E)
 *   <li>ASCII whitespace (tab, LF, CR)
 *   <li>Em dash (—) and en dash (–) — financial journalism standard
 *   <li>Curly quotes (‘’“”) — copy-editor standard
 *   <li>Ellipsis (…)
 *   <li>Multiplication sign (×) — model uses for multipliers
 *       (e.g. "100× the trailing median")
 *   <li>Degree sign (°) — rare but valid
 *   <li>Less-than-equal / greater-than-equal (≤ ≥) — rare but valid
 * </ul>
 *
 * <p>Everything else (CJK, Cyrillic, Arabic, control chars, exotic
 * punctuation) is rejected.
 */
final class ProseCharCheck {

    private ProseCharCheck() {}

    /**
     * Returns the first non-allowed character in {@code prose} as a
     * human-readable display string (the character plus its hex
     * codepoint), or {@code null} if every character is allowed.
     */
    static String firstNonAllowedChar(final String prose) {
        if (prose == null || prose.isEmpty()) return null;
        int i = 0;
        while (i < prose.length()) {
            int cp = prose.codePointAt(i);
            if (!isAllowed(cp)) {
                return new StringBuilder()
                        .append("'")
                        .appendCodePoint(cp)
                        .append("' (U+")
                        .append(String.format("%04X", cp))
                        .append(")")
                        .toString();
            }
            i += Character.charCount(cp);
        }
        return null;
    }

    private static boolean isAllowed(final int cp) {
        // ASCII printable + common whitespace
        if (cp >= 0x20 && cp <= 0x7E) return true;
        if (cp == 0x09 || cp == 0x0A || cp == 0x0D) return true;

        // Journalism / typography punctuation
        switch (cp) {
            case 0x00A9:  // copyright ©
            case 0x00AE:  // registered ®
            case 0x00B0:  // degree °
            case 0x00B1:  // plus-minus ±
            case 0x00B2:  // superscript 2
            case 0x00B3:  // superscript 3
            case 0x00B5:  // MICRO SIGN µ — used in "µs" (microseconds);
                          // post_cancel + layering scorers emit
                          // median_lifetime_microseconds with µs suffix.
                          // CRITICAL: this is the canonical microseconds
                          // notation; rejecting it would cause every
                          // sub-ms event narrative to fail verifier.
            case 0x03BC:  // GREEK SMALL LETTER MU μ — models sometimes
                          // emit this variant instead of U+00B5.
                          // Visually identical to most readers; allow both.
            case 0x00BC:  // ¼
            case 0x00BD:  // ½
            case 0x00BE:  // ¾
            case 0x00D7:  // multiplication × (model uses for multipliers)
            case 0x00F7:  // division ÷
            case 0x2013:  // en dash –
            case 0x2014:  // em dash —
            case 0x2018:  // left single quote '
            case 0x2019:  // right single quote (apostrophe) '
            case 0x201C:  // left double quote "
            case 0x201D:  // right double quote "
            case 0x2026:  // ellipsis …
            case 0x2032:  // prime ′
            case 0x2033:  // double prime ″
            case 0x2122:  // trademark ™
            case 0x2212:  // minus sign − (different from hyphen-minus)
            case 0x2264:  // ≤
            case 0x2265:  // ≥
            case 0x2248:  // ≈
                return true;
        }
        return false;
    }
}
