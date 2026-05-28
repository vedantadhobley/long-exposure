package com.longexposure.narration;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
     * Ticker-shaped tokens — 2-5 uppercase letters, optionally followed by
     * either a class-share suffix ({@code .A} / {@code .B}, e.g.
     * {@code BRK.B}) or a single trailing {@code .}/{@code =} (IEX symbols
     * sometimes carry suffix characters, e.g. {@code OTAI=}). Anchored to
     * word boundaries.
     *
     * <p>The {@code \.[A-Z]{1,2}} alternative is what fixes the dotted-ticker
     * false-positive: without it the engine matched just {@code BRK.} out of
     * {@code BRK.B} (the {@code \b} falls between {@code .} and {@code B}),
     * then failed the day-symbol lookup. Now {@code BRK.B} is captured whole
     * and matches the symbol set directly. Shared by SYNTHESIZE and the
     * weekly AGGREGATE verification (both route through this class).
     */
    private static final Pattern TICKER_RE =
            Pattern.compile("\\b([A-Z]{2,5}(?:\\.[A-Z]{1,2}|[.=])?)\\b");

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

    /**
     * Words that imply <em>intent</em>. Explicitly forbidden by the
     * SYNTHESIZE + AGGREGATE prompts (the pattern-catalog rule: describe
     * shape, not intent), but the verifier didn't previously check. Observed
     * leak 2026-05-22: a synthesis paragraph contained
     * <em>"active, fleeting order-book manipulation across leveraged
     * vehicles"</em> — passed verifier because it had no number / ticker
     * mismatch. The retry mechanism re-rolls at temp variance and clears on
     * the next attempt almost every time, so a denylist reject is cheap.
     *
     * <p>Word-boundary-anchored. {@code manipul\w*} catches manipulation /
     * manipulator / manipulate; {@code spoof\w*} catches spoof / spoofing /
     * spoofed; {@code illegal}, {@code fake} are literal.
     *
     * <p><b>Removed 2026-05-28</b>: {@code gam(?:ing|ed)} was originally
     * included to catch "gaming the system" / "gaming the book". But it
     * false-positives on "gaming stocks" / "gaming sector" — legitimate
     * sector references for DraftKings (DKNG), Roblox, etc. Observed
     * 2026-05-28 on 05-11 synthesis: "iceberg orders masking volume in
     * gaming stocks like DKNG" — sector use, not manipulation, but flagged
     * by the regex and re-rolled 3× without resolution. Dropped. "gaming
     * the system" is rare enough in finance prose that defensive prompt
     * language ("describe shape, not intent") is sufficient.
     */
    private static final Pattern INTENT_WORDS = Pattern.compile(
            "\\b(?:manipul\\w*|spoof\\w*|front[- ]?run\\w*|wash[- ]?trad\\w*|"
                    + "illegal\\w*|fake\\w*)\\b",
            Pattern.CASE_INSENSITIVE);

    public SynthesisVerifier() {}

    /**
     * Extract the distinct ticker-shaped tokens from arbitrary prose, using
     * the same regex + acronym filter as the fabrication check. Used by the
     * weekly AGGREGATE stage to build the legitimate-ticker universe for a
     * week (= union of tickers across that week's daily syntheses), which is
     * then passed back into {@link #verify} as the allowed-symbol set. Keeps
     * the dotted-ticker handling in one place across both stages.
     */
    public static Set<String> extractTickers(final String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        Matcher m = TICKER_RE.matcher(text);
        while (m.find()) {
            String t = m.group(1);
            if (!ALLOWED_NON_TICKERS.contains(t)) out.add(t);
        }
        return out;
    }

    /**
     * Original 3-arg form — runs ticker fabrication, number grounding, and
     * intent denylist. Does NOT run the per-claim attribution check. Used by
     * AggregateWeek / Quarter / Year stages (which currently don't have a
     * structured per-period claim-truth map; their attribution would need to
     * sum across days). Kept as a backward-compatible entry point.
     *
     * <p>SYNTHESIZE-day callers should use the 5-arg overload below to
     * additionally run {@link AttributionVerifier}.
     */
    public Result verify(final String prose,
                         final Set<String> daySymbols,
                         final String numberHaystack) {
        return verify(prose, daySymbols, numberHaystack, null, null);
    }

    /**
     * Full verification with per-claim attribution check.
     *
     * @param prose             synthesis paragraph from the LLM
     * @param daySymbols        every symbol that has a narration today
     * @param numberHaystack    concatenation of narrations + interpretations
     *                          + day_aggregates JSON, the number-grounding
     *                          haystack
     * @param bySymbolByScorer  per-symbol per-scorer count map (truth source
     *                          for symbol-attributed scorer-specific claims).
     *                          {@code null} skips the attribution check.
     * @param bySymbolTotal     per-symbol total event count (truth source for
     *                          symbol-attributed generic event-noun claims).
     *                          {@code null} skips that branch even if
     *                          bySymbolByScorer is provided.
     */
    public Result verify(final String prose,
                         final Set<String> daySymbols,
                         final String numberHaystack,
                         final Map<String, Map<String, Integer>> bySymbolByScorer,
                         final Map<String, Integer> bySymbolTotal) {
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
            normalizedDaySymbols.add(s);                          // "BRK.B", "OTAI="
            normalizedDaySymbols.add(s.replaceAll("[.=]$", ""));  // "OTAI" (trailing suffix dropped)
            int dot = s.indexOf('.');
            if (dot > 0) normalizedDaySymbols.add(s.substring(0, dot)); // "BRK.B" -> "BRK" shorthand
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
        Set<String> haystackNums     = GroundingVerifier.canonicalNumbersIn(numberHaystack);
        Set<String> haystackWordNums = GroundingVerifier.cardinalWordNumbersIn(numberHaystack);
        Set<String> proseNums        = GroundingVerifier.canonicalNumbersIn(prose);
        Set<String> proseWordNums    = GroundingVerifier.cardinalWordNumbersIn(prose);

        int numbersChecked = proseNums.size() + proseWordNums.size();
        for (String n : proseNums) {
            if (n.length() < 2) continue;                       // single-digit noise
            if (n.equals("2026") || n.equals("2025")) continue; // year tokens

            if (haystackNums.contains(n))     continue;
            if (haystackWordNums.contains(n)) continue;

            // Try precision-rounded equivalence — same logic as
            // InterpretationVerifier. Numbers ≥ 10 get d=0/1/2/3/4 rounds in
            // FLOOR/HALF_UP/CEILING modes; smaller numbers d=1..4 only.
            if (precisionEquivalent(n, haystackNums)) continue;

            mismatches.add("prose number \"" + n
                    + "\" not found in narrations / interpretations / day_aggregates");
        }

        // Cardinal word-form numerals in prose ("six halts", "ten layering")
        // must also ground. No length filter — even single-digit word-forms
        // ("five", "six") are unambiguous and the exact failure mode we
        // observed 2026-05-28: 05-12 daily synthesis had 5 wrong word-form
        // counts ("eight"/"ten"/"six"/"four"/"three") that bypassed
        // verification entirely. Counts are integers (no rounding tolerance
        // needed); skip precisionEquivalent.
        for (String wn : proseWordNums) {
            if (haystackNums.contains(wn))     continue;
            if (haystackWordNums.contains(wn)) continue;
            mismatches.add("prose cardinal word-form \"" + wn
                    + "\" not found in narrations / interpretations / day_aggregates");
        }

        // ─── Intent-claim denylist ──────────────────────────────────────────
        // The SYNTHESIZE + AGGREGATE prompts already say "do not speculate
        // about intent", but the 2026-05-22 synthesis leaked
        // "active, fleeting order-book manipulation across leveraged vehicles"
        // — verifier passed because it only checks numbers + tickers. Pure-
        // prose denylist; no haystack. A hit triggers the standard verifier-
        // driven retry (3 attempts, temp variance) which clears the leak.
        Matcher dm = INTENT_WORDS.matcher(prose);
        Set<String> intentHits = new HashSet<>();
        while (dm.find()) intentHits.add(dm.group().toLowerCase());
        for (String hit : intentHits) {
            mismatches.add("prose contains intent-claim word \"" + hit
                    + "\" — pattern-catalog rule: describe shape, not intent");
        }

        // ─── Per-claim attribution check ────────────────────────────────────
        // The structural fix for misattribution surfaced 2026-05-28: "TQQQ
        // with ten X events" passes when 10 is in the haystack as some OTHER
        // count (e.g. the day's halt count), but the (TQQQ, 10, X) triple
        // doesn't match data. AttributionVerifier extracts (subject, count,
        // scorer-type) tuples from prose and checks each against the truth
        // maps. Skipped if maps not provided (AggregateWeek/Quarter/Year
        // currently don't supply them).
        int claimsChecked = 0;
        if (bySymbolByScorer != null && bySymbolTotal != null) {
            AttributionVerifier av = new AttributionVerifier();
            AttributionVerifier.Result ar = av.verify(prose, bySymbolByScorer, bySymbolTotal);
            mismatches.addAll(ar.mismatches());
            claimsChecked = ar.claimsChecked();
        }

        return new Result(mismatches.isEmpty(), mismatches,
                proseTickers.size(), numbersChecked + claimsChecked);
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
