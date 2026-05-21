package com.longexposure.narration;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns raw NASDAQ {@code Security Name} strings into journalist-shaped
 * display names. Used by {@link SubjectSubstitution} at presentation
 * time only — never sent to the LLM and never stored.
 *
 * <p><b>Algorithm</b>: walk tokens right-to-left, dropping ones that are
 * SEC filing decoration ("Common", "Stock", "Class", "A", "Series", "1",
 * "(Ireland)", etc.), and stop at the first token that's part of the
 * actual company identity ("Inc.", "Corp.", "plc", "Limited", or a
 * proper noun).
 *
 * <p><b>On the FILING_DECORATION set being a list</b>: this enumerates a
 * small, stable, principle-defined domain — SEC filing-class vocabulary.
 * It is not the same shape as a "denylist of LLM behaviors" that grows
 * with every observed failure. SEC filing terminology is what it is;
 * the set has not grown beyond ~10 tokens in 30 years.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code "Odyssey Therapeutics, Inc. - Common Stock"} →
 *       {@code "Odyssey Therapeutics, Inc."}
 *   <li>{@code "Accenture plc Class A Ordinary Shares (Ireland)"} →
 *       {@code "Accenture plc"}
 *   <li>{@code "AllianzIM U.S. Large Cap Buffer20 May ETF"} →
 *       {@code "AllianzIM U.S. Large Cap Buffer20 May ETF"} (unchanged)
 *   <li>{@code "Vanguard Russell 2000 ETF"} → unchanged
 *   <li>{@code "Invesco QQQ Trust, Series 1"} → {@code "Invesco QQQ Trust"}
 * </ul>
 */
public final class CompanyNameNormalizer {

    private CompanyNameNormalizer() {}

    /**
     * SEC filing decoration tokens. Dropped when they appear at the
     * trailing end of a security name. NOT a list of "company suffixes"
     * — entity-type suffixes like "Inc.", "Corp.", "plc", "Limited",
     * "Ltd" are part of the company identity and must be kept.
     */
    private static final Set<String> FILING_DECORATION = Set.of(
            "common", "stock", "shares", "ordinary", "ordinaries",
            "class", "series",
            "adr", "ads",
            "preferred", "preference",
            "depositary", "receipts"
    );

    /**
     * Multi-word SEC filing suffixes. Stripped before the token walk
     * because the words individually overlap with entity-type tokens
     * we need to preserve ("Limited" as entity suffix is legitimate
     * for foreign issuers, but "Common Units representing Limited
     * Partner Interests" is filing decoration on an MLP).
     */
    private static final Pattern[] MULTIWORD_SUFFIXES = new Pattern[] {
            // Master Limited Partnership common-units filing
            Pattern.compile(
                    "\\s*-?\\s*Common\\s+Units\\s+representing\\s+Limited\\s+Partner\\s+Interests\\s*$",
                    Pattern.CASE_INSENSITIVE),
            // ETN-style "due <Date>" maturity suffixes — informative for
            // structured-product accuracy but not part of issuer name.
            // Matches "due October", "due October 2030", "due October 30",
            // "due October 30, 2043", and variants with separating commas.
            Pattern.compile(
                    "\\s+due\\s+[A-Za-z]+(?:\\s+\\d{1,2})?(?:,?\\s+\\d{4})?\\s*$",
                    Pattern.CASE_INSENSITIVE),
    };

    /** Punctuation-only tokens (dashes, lone commas) act as separators. */
    private static final Pattern PUNCTUATION_ONLY = Pattern.compile("\\p{Punct}+");

    /** "(Ireland)", "(Bermuda)", "(Bahamas)" — country-of-origin annotations. */
    private static final Pattern COUNTRY_PAREN = Pattern.compile("\\([A-Z][a-z]+\\)");

    public static String normalize(final String raw) {
        if (raw == null) return null;
        String s = raw.trim().replaceAll("\\s+", " ");
        if (s.isEmpty()) return s;

        // Pre-strip multi-word filing suffixes that token-walking can't handle.
        for (Pattern p : MULTIWORD_SUFFIXES) {
            s = p.matcher(s).replaceFirst("").trim();
        }
        if (s.isEmpty()) return s;

        String[] tokens = s.split(" ");
        int keep = tokens.length;
        while (keep > 0 && isDroppable(tokens[keep - 1])) {
            keep--;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keep; i++) {
            if (i > 0) sb.append(' ');
            sb.append(tokens[i]);
        }
        // Strip dangling trailing punctuation/whitespace ("X -" → "X").
        return sb.toString().replaceAll("[,\\s\\-]+$", "").trim();
    }

    private static boolean isDroppable(final String token) {
        if (token == null || token.isEmpty()) return true;
        if (PUNCTUATION_ONLY.matcher(token).matches()) return true;
        if (COUNTRY_PAREN.matcher(token).matches()) return true;

        String normalized = token.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (normalized.isEmpty()) return true;                            // punctuation-only stripped to ""
        if (FILING_DECORATION.contains(normalized)) return true;          // SEC filing decoration
        if (normalized.length() == 1 && normalized.matches("[a-z]")) return true;  // class letter
        if (normalized.matches("\\d+")) return true;                      // series number
        return false;
    }
}
