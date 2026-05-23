package com.longexposure.narration;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-code grounding verifier for SYNTHESIZE output. Looser than the
 * per-event verifiers (synthesis is inherently interpretive and may
 * legitimately cite counts derived from narrations) but strict on the
 * two failure modes that would damage credibility:
 *
 * <ul>
 *   <li><b>Ticker fabrication</b> — every ticker-shaped token in the
 *       synthesis prose must appear in the set of symbols actually
 *       narrated today. If the model writes "TSLA had a rough day"
 *       but no TSLA event was scored today, that's a fabrication.
 *   <li><b>Number grounding</b> — every numeric token in synthesis
 *       must appear in either the narrations text, the interpretation
 *       text, or the day_aggregates JSON. Uses the same precision-
 *       matching logic as {@link InterpretationVerifier} so journalist
 *       rounding ("approximately 4.9K orders" matching 4,895) doesn't
 *       false-positive.
 * </ul>
 *
 * <p>Things this verifier does NOT check (because SYNTHESIZE's prompt
 * rules + model judgment are doing the work):
 * <ul>
 *   <li>External news / event claims ("the FOMC announced", "earnings
 *       beats") — the prompt explicitly forbids; spot-check on output.
 *   <li>Causal claims about why patterns occurred — interpretive by
 *       nature; the catalog vocabulary discipline applies via prompt.
 *   <li>Editorializing severity — same.
 * </ul>
 */
public final class SynthesisVerifier {

    /** Same number regex as {@link GroundingVerifier}. */
    private static final Pattern NUMBER_RE = Pattern.compile("\\d[\\d,_]*(?:\\.\\d+)?");

    /**
     * Ticker-shaped tokens — 2-5 uppercase letters, optionally followed
     * by a "." or "=" suffix (handles "OTAI=" — IEX symbols sometimes
     * carry suffix characters). Anchored to word boundaries.
     */
    private static final Pattern TICKER_RE = Pattern.compile("\\b([A-Z]{2,5}[.=]?)\\b");

    /**
     * Common English / finance acronyms that look like tickers. Synthesis
     * prose may legitimately use these regardless of whether they appear
     * in the symbol set. Same spirit as the boring-numbers list in
     * {@link GroundingVerifier}.
     */
    private static final Set<String> ALLOWED_NON_TICKERS = Set.of(
            "IEX", "NYSE", "NASDAQ", "ETF", "ETN", "ETP", "MCB", "MCB1", "MCB2", "MCB3",
            "LULD", "T1", "T2", "IPO", "IPO1", "IPO2", "IPO3", "IPOD",
            "NMS", "SEC", "FOMC", "SPX", "DJI",
            "NBBO", "VWAP", "TWAP", "HFT", "SRO", "ATS",
            "ET", "EDT", "EST", "UTC", "AM", "PM",
            "USD", "GMT", "API", "DPLS", "TOPS", "DEEP"
    );

    public SynthesisVerifier() {}

    /**
     * @param prose          synthesis paragraph from the LLM
     * @param daySymbols     every symbol that has a narration on this date
     * @param numberHaystack concatenation of all narrations + interpretations
     *                       + day_aggregates JSON, used as the number-grounding
     *                       source. Provided pre-built so the activity can
     *                       construct it once.
     */
    public Result verify(final String prose,
                         final Set<String> daySymbols,
                         final String numberHaystack) {
        List<String> mismatches = new ArrayList<>();

        // ─── Ticker fabrication check ───────────────────────────────────────
        // Build a normalized day-symbols set that includes BOTH the raw form
        // ("OTAI=", "BRK.B") AND the punctuation-stripped form ("OTAI", "BRK").
        // IEX SecurityDirectory carries suffix-punctuation symbols verbatim
        // ("OTAI=") while journalist prose typically drops them. Matching
        // against either form lets the verifier accept that legitimate drop
        // without flagging fabrication.
        Set<String> normalizedDaySymbols = new HashSet<>();
        for (String s : daySymbols) {
            normalizedDaySymbols.add(s);
            normalizedDaySymbols.add(s.replaceAll("[.=]$", ""));
        }

        Set<String> proseTickers = new HashSet<>();
        Matcher tm = TICKER_RE.matcher(prose);
        while (tm.find()) proseTickers.add(tm.group(1));

        for (String t : proseTickers) {
            if (ALLOWED_NON_TICKERS.contains(t)) continue;
            if (normalizedDaySymbols.contains(t)) continue;
            if (normalizedDaySymbols.contains(t.replaceAll("[.=]$", ""))) continue;
            mismatches.add("prose ticker \"" + t
                    + "\" not in today's narrated symbols — possible fabrication");
        }

        // ─── Number grounding check ─────────────────────────────────────────
        Set<String> haystackNums = GroundingVerifier.canonicalNumbersIn(numberHaystack);
        Set<String> proseNums    = GroundingVerifier.canonicalNumbersIn(prose);

        int numbersChecked = proseNums.size();
        for (String n : proseNums) {
            if (n.length() < 2) continue;                       // single-digit noise
            if (n.equals("2026") || n.equals("2025")) continue; // year tokens

            if (haystackNums.contains(n)) continue;

            // Try precision-rounded equivalence — same logic as
            // InterpretationVerifier. Numbers ≥ 10 get d=0/1/2/3/4 rounds in
            // FLOOR/HALF_UP/CEILING modes; smaller numbers d=1..4 only.
            if (precisionEquivalent(n, haystackNums)) continue;

            mismatches.add("prose number \"" + n
                    + "\" not found in narrations / interpretations / day_aggregates");
        }

        return new Result(mismatches.isEmpty(), mismatches, proseTickers.size(), numbersChecked);
    }

    /**
     * True if {@code proseNum} equals some haystack number rounded to a
     * journalist-acceptable precision. Uses the same logic family as
     * {@link InterpretationVerifier#verify}.
     */
    private static boolean precisionEquivalent(final String proseNum, final Set<String> haystackNums) {
        java.math.BigDecimal proseBd;
        try {
            proseBd = new java.math.BigDecimal(proseNum.replace(",", "").replace("_", ""));
        } catch (NumberFormatException e) {
            return false;
        }
        int scale = Math.max(proseBd.stripTrailingZeros().scale(), 0);

        for (String h : haystackNums) {
            java.math.BigDecimal hbd;
            try {
                hbd = new java.math.BigDecimal(h);
            } catch (NumberFormatException e) {
                continue;
            }
            // Match at the prose's precision under multiple rounding modes.
            // (HALF_UP, FLOOR, CEILING — covers all common journalist
            // rounding conventions.)
            if (matchesAtScale(hbd, proseBd, scale, java.math.RoundingMode.HALF_UP)) return true;
            if (matchesAtScale(hbd, proseBd, scale, java.math.RoundingMode.FLOOR))   return true;
            if (matchesAtScale(hbd, proseBd, scale, java.math.RoundingMode.CEILING)) return true;
        }
        return false;
    }

    private static boolean matchesAtScale(final java.math.BigDecimal h,
                                           final java.math.BigDecimal prose,
                                           final int scale,
                                           final java.math.RoundingMode mode) {
        java.math.BigDecimal rounded = h.setScale(scale, mode).stripTrailingZeros();
        java.math.BigDecimal proseNorm = prose.stripTrailingZeros();
        return rounded.compareTo(proseNorm) == 0;
    }

    /**
     * Result of a SYNTHESIZE verification pass.
     *
     * @param passed          true if no mismatches
     * @param mismatches      human-readable list of issues
     * @param tickersChecked  distinct ticker-shaped tokens found in prose
     * @param numbersChecked  distinct number tokens found in prose
     */
    public record Result(boolean passed, List<String> mismatches,
                         int tickersChecked, int numbersChecked) {}
}
