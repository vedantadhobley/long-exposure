package com.longexposure.synth;

/**
 * Pre-computes the EXACT allowed streak phrasings for a rollup tier based on
 * the number of prior periods available. Replaces the earlier "with N prior
 * weeks the longest honest streak is N+1" prompt language — which trusted
 * the model to do correct arithmetic and occasionally produced "fifth
 * consecutive week" off 2 priors.
 *
 * <p>Used by {@code AggregateWeekActivityImpl} (unit "week"),
 * {@code AggregateQuarterActivityImpl} (unit "quarter"), and
 * {@code AggregateYearActivityImpl} (unit "year").
 *
 * <p>The shape the user prompt sees:
 * <pre>
 * ALLOWED STREAK PHRASING (use exactly one of these forms verbatim, or OMIT):
 *   - "a second consecutive week"
 *   - "a third consecutive week"
 * FORBIDDEN: any ordinal larger than "third" — would invent weeks not in your data.
 * </pre>
 *
 * <p>The system prompt then instructs: "any streak claim MUST exactly match
 * an entry in ALLOWED STREAK PHRASING — there is no shortcut." This converts
 * an arithmetic constraint the model occasionally violates into a verbatim-
 * match constraint that LLMs respect with very high reliability.
 *
 * <p>The numeric streak verifier in {@code SynthesisVerifier} stays as the
 * safety net behind this prompt-level fix.
 */
public final class StreakPhrasings {

    private StreakPhrasings() {}

    /** Ordinal words used for streak phrasings. Index = the streak count (so [2]="second"). */
    private static final String[] ORDINALS = {
            "",         // 0 — unused (streak == 0 has no phrasing)
            "",         // 1 — unused (a streak of 1 is just "this week", no consecutive claim)
            "second",   // 2 — "a second consecutive week" = this week + 1 prior
            "third",    // 3
            "fourth",   // 4
            "fifth",    // 5
            "sixth",    // 6
            "seventh",  // 7
            "eighth",   // 8
            "ninth",    // 9
            "tenth",    // 10
            "eleventh", // 11
            "twelfth",  // 12
            "thirteenth", // 13
            "fourteenth", // 14
    };

    /**
     * Build the ALLOWED STREAK PHRASING block for a rollup user prompt.
     *
     * @param priorCount  number of prior-period rollups available (e.g. {@code priors.size()})
     * @param unit        the singular noun for the period ("week", "quarter", "year")
     * @return the block as a multi-line string, ready to append to the user prompt
     */
    public static String allowedStreakPhrasings(final int priorCount, final String unit) {
        if (priorCount <= 0) {
            return "ALLOWED STREAK PHRASING: NONE.\n"
                    + "  The data does not support any '" + unit + "-over-" + unit
                    + "' comparison or 'consecutive " + unit + "' streak claim.\n"
                    + "  OMIT all such phrasing entirely. Do not write 'second', 'third', "
                    + "or any other consecutive-" + unit + " claim.";
        }
        int maxStreak = Math.min(priorCount + 1, ORDINALS.length - 1);
        StringBuilder sb = new StringBuilder(512);
        sb.append("ALLOWED STREAK PHRASING (use exactly one of these forms verbatim, or OMIT):\n");
        for (int n = 2; n <= maxStreak; n++) {
            sb.append("  - \"a ").append(ORDINALS[n]).append(" consecutive ").append(unit).append("\"\n");
        }
        // Forbidden line names the next ordinal up so the model can't reach for it.
        if (maxStreak + 1 < ORDINALS.length) {
            sb.append("FORBIDDEN: any ordinal at or beyond \"").append(ORDINALS[maxStreak + 1])
              .append(" consecutive ").append(unit).append("\" — would invent ").append(unit)
              .append("s not present in your data.\n");
        }
        sb.append("If you do not need a streak claim, omit one entirely — 'continuing from last ")
          .append(unit).append("' is always safe and does not assert a count.");
        return sb.toString();
    }
}
