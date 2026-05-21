package com.longexposure.scoring;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strips SEC-filing artifacts from raw company names so the LLM sees a
 * journalist-shaped string. Inputs look like
 * {@code "Odyssey Therapeutics, Inc. - Common Stock"} or
 * {@code "Accenture plc Class A Ordinary Shares (Ireland)"};
 * outputs look like {@code "Odyssey Therapeutics, Inc."} and
 * {@code "Accenture plc"}.
 *
 * <p>The purpose is reducing the model's incentive to "improve" the name
 * by stripping noise on its own — which sometimes produced the right
 * result (Intel Corporation → Intel Corp.) but other times invented a
 * different company entirely ("Mayweather Inc." for an AllianzIM ETF).
 * Cleaner inputs reduce that pressure.
 *
 * <p>This is normalization, not validation. The output is still meant to
 * be a recognizable company name — we strip filing decoration but never
 * invent words. {@link com.longexposure.narration.GroundingVerifier}
 * does the matching-back check downstream.
 */
public final class CompanyNameNormalizer {

    private CompanyNameNormalizer() {}

    /**
     * Suffix tokens that consistently appear at the end of NASDAQ /
     * IEX SecurityDirectory company strings. Matched case-insensitively
     * with optional leading hyphen/comma/space punctuation. Order
     * matters — longer phrases first so partial matches don't shadow.
     */
    private static final Pattern[] SUFFIX_PATTERNS = new Pattern[] {
            // Filing-class qualifiers
            Pattern.compile("\\s+-\\s+Class\\s+[A-Z]\\s+Common\\s+Stock\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s+-\\s+Class\\s+[A-Z]\\s+Ordinary\\s+Shares\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s+Class\\s+[A-Z]\\s+Common\\s+Stock\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s+Class\\s+[A-Z]\\s+Ordinary\\s+Shares\\s*$", Pattern.CASE_INSENSITIVE),
            // Common-stock suffixes
            Pattern.compile("\\s+-\\s+Common\\s+Stock\\s+ADR\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s+Common\\s+Stock\\s+ADR\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s+-\\s+Common\\s+Stock\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile(",?\\s+Common\\s+Stock\\s*$", Pattern.CASE_INSENSITIVE),
            // ETF series suffixes ("Trust, Series 1", "Trust Series 1")
            Pattern.compile(",?\\s+Series\\s+\\d+\\s*$"),
            // Ordinary-shares qualifiers
            Pattern.compile("\\s+Ordinary\\s+Shares\\s*$", Pattern.CASE_INSENSITIVE),
            // Trailing exchange-of-origin parentheticals: "(Ireland)", "(Bermuda)" etc.
            Pattern.compile("\\s+\\((?:[A-Z][a-z]+)\\)\\s*$"),
            // Trailing comma + extra word artifacts: ", - Common Stock" partials
            Pattern.compile("\\s*,\\s*-\\s*$"),
    };

    /** Whole-string normalizations that don't fit the suffix pattern shape. */
    private static final Set<Pattern> WHOLESTRING_NOISE = Set.of(
            // Double spaces (seen in "British American Tobacco  Industries, p.l.c.")
            Pattern.compile("\\s{2,}")
    );

    /**
     * Apply suffix stripping + double-space collapsing. Returns
     * {@code null} for null input. Repeats suffix stripping until no
     * more progress is made, so "X - Common Stock Common Stock" collapses
     * down (defensive — shouldn't happen in practice).
     */
    public static String normalize(final String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return s;

        // Collapse runs of whitespace first so suffix regexes match cleanly.
        for (Pattern p : WHOLESTRING_NOISE) {
            s = p.matcher(s).replaceAll(" ");
        }

        // Iterate suffix stripping until fixed point.
        boolean changed = true;
        int safety = 8;   // guard against runaway loop
        while (changed && safety-- > 0) {
            changed = false;
            for (Pattern p : SUFFIX_PATTERNS) {
                String next = p.matcher(s).replaceFirst("");
                if (!next.equals(s)) {
                    s = next.trim();
                    changed = true;
                }
            }
        }
        return s;
    }
}
