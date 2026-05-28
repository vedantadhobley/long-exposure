package com.longexposure.narration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-code attribution verifier for the SYNTHESIZE / AGGREGATE-week tiers.
 * Catches the misattribution failure mode the {@link SynthesisVerifier}
 * cannot reach: prose where the model cites a count for a specific symbol
 * (or scorer-type) that doesn't match the day's actual count.
 *
 * <p><b>Observed bugs this catches</b> (2026-05-28 audit):
 * <ul>
 *   <li>05-13 v6 synthesis: "TQQQ which recorded ten distinct order-deletion
 *       events" — TQQQ actually had 20 liquidity_withdrawals. "10" passes
 *       {@link SynthesisVerifier} because the haystack contains 10 (as the
 *       day's halt count), but the (symbol=TQQQ, count=10,
 *       scorer=liquidity_withdrawal) tuple does not match the
 *       by_symbol_by_scorer truth.
 *   <li>05-12 v5 (pre-word-form-fix) synthesis: "eight TQQQ events" (actual
 *       14), "six QQQ withdrawals" (actual 14), etc. Word-form bypass
 *       fixed structurally in {@code GroundingVerifier.cardinalWordNumbersIn};
 *       this verifier closes the attribution gap on top of that.
 * </ul>
 *
 * <p><b>Extraction approach</b>: tokenize prose, scan for scorer-noun tokens
 * (e.g. "halts", "iceberg patterns", "liquidity withdrawals"), then look in
 * a bounded window before/after for the nearest count and subject ticker.
 * Three claim shapes are detected:
 * <ol>
 *   <li>Subject-led: <em>"TQQQ recorded ten layering events"</em>
 *   <li>Verb-led:    <em>"four post-cancel clusters on IWM"</em>
 *   <li>Possessive:  <em>"QQQ's six withdrawals"</em>
 * </ol>
 *
 * <p>Numbers are extracted in both digit and word-form (delegating to
 * {@link GroundingVerifier#canonicalNumbersIn} and
 * {@link GroundingVerifier#cardinalWordNumbersIn}); the comparison is
 * canonical-digit.
 *
 * <p><b>Out of scope</b> (intentionally — false-positive guards):
 * <ul>
 *   <li>Multi-symbol attribution ("DGP, TQQQ, and QQQ collectively generated
 *       49"). Compositional; sum check is correct but the extraction would
 *       false-positive on "DGP, TQQQ, ... 49 halts" if there's no
 *       "collectively" connector. Tracked as a separate todo.
 *   <li>Generic event nouns ("incidents", "events", "patterns") without a
 *       scorer-type qualifier. These check against the symbol's TOTAL event
 *       count, not a per-scorer count. Handled via {@code bySymbolTotal}.
 *   <li>Vague counts ("hundreds of orders", "thousands of deletions"). Word
 *       "hundreds" / "thousands" are plural-only forms not in the cardinal
 *       set — they pass through this verifier untouched.
 * </ul>
 */
public final class AttributionVerifier {

    /**
     * Prose nouns that name a specific scorer type. Plural and singular forms,
     * lowercased. Each maps to the canonical scorer_id from
     * {@code EventScorerRegistry}. Order matters in the {@link #SCORER_NOUN_RE}
     * regex below: longer multi-word phrases must come first so the engine
     * matches "iceberg pattern" before "iceberg".
     */
    private static final Map<String, String> NOUN_TO_SCORER = new HashMap<>();
    static {
        // Halt — wide synonym set: "halt" / "suspension" / "interruption"
        for (String n : new String[]{"halt", "halts", "trading halt", "trading halts",
                "trading suspension", "trading suspensions", "regulatory halt",
                "regulatory halts", "regulatory suspension", "regulatory suspensions",
                "trading interruption", "trading interruptions", "pre-market halt",
                "pre-market halts"}) {
            NOUN_TO_SCORER.put(n, "halt");
        }
        // Large trade
        for (String n : new String[]{"large trade", "large trades", "large block",
                "large blocks", "large-block trade", "large-block trades",
                "block trade", "block trades", "block execution",
                "block executions", "block print", "block prints"}) {
            NOUN_TO_SCORER.put(n, "large_trade");
        }
        // Sweep
        for (String n : new String[]{"sweep", "sweeps", "sweep event", "sweep events",
                "sweep pattern", "sweep patterns", "multi-level sweep",
                "multi-level sweeps", "aggressive sweep", "aggressive sweeps"}) {
            NOUN_TO_SCORER.put(n, "sweep");
        }
        // Iceberg
        for (String n : new String[]{"iceberg", "icebergs", "iceberg pattern",
                "iceberg patterns", "iceberg order", "iceberg orders",
                "iceberg execution", "iceberg executions"}) {
            NOUN_TO_SCORER.put(n, "iceberg");
        }
        // Layering
        for (String n : new String[]{"layering", "layering event", "layering events",
                "layering pattern", "layering patterns", "layering burst",
                "layering bursts"}) {
            NOUN_TO_SCORER.put(n, "layering");
        }
        // Post-cancel cluster (with and without hyphen)
        for (String n : new String[]{"post-cancel cluster", "post-cancel clusters",
                "post-cancel burst", "post-cancel bursts", "post-cancel event",
                "post-cancel events", "post cancel cluster", "post cancel clusters"}) {
            NOUN_TO_SCORER.put(n, "post_cancel_cluster");
        }
        // Liquidity withdrawal
        for (String n : new String[]{"liquidity withdrawal", "liquidity withdrawals",
                "quote pull", "quote pulls", "cancel storm", "cancel storms",
                "depth contraction", "depth contractions",
                "order-deletion event", "order-deletion events",
                "order deletion event", "order deletion events"}) {
            NOUN_TO_SCORER.put(n, "liquidity_withdrawal");
        }
        // Volume deviation
        for (String n : new String[]{"volume surge", "volume surges",
                "volume deviation", "volume deviations", "volume spike",
                "volume spikes"}) {
            NOUN_TO_SCORER.put(n, "volume_deviation");
        }
        // Time-in-book drift
        for (String n : new String[]{"time-in-book drift", "drift event",
                "drift events", "lifetime regime shift"}) {
            NOUN_TO_SCORER.put(n, "time_in_book_drift");
        }
    }

    /**
     * Generic nouns that don't specify a scorer — match against the symbol's
     * total event count rather than a per-scorer count.
     */
    private static final Set<String> GENERIC_NOUNS = Set.of(
            "event", "events", "incident", "incidents",
            "occurrence", "occurrences", "pattern", "patterns",
            "occasion", "occasions", "instance", "instances",
            "print", "prints"
    );

    /**
     * Regex matching any scorer-noun phrase or generic event noun. Longest-
     * first ordering (built from {@link #NOUN_TO_SCORER} + {@link #GENERIC_NOUNS}
     * sorted by length descending) ensures "iceberg pattern" matches before
     * "iceberg". Case-insensitive.
     *
     * <p><b>Hyphen/space equivalence</b> (2026-05-28 evening): finance prose
     * commonly hyphenates compound nouns ("depth-contraction", "order-deletion"
     * etc.). The vocabulary is stored space-separated ("depth contraction");
     * the regex is built so each inter-word whitespace can match a hyphen OR
     * whitespace. Without this, prose like "QQQ saw four depth-contraction
     * events" extracted no claim because the noun didn't match.
     */
    private static final Pattern NOUN_RE;
    static {
        List<String> nouns = new ArrayList<>();
        nouns.addAll(NOUN_TO_SCORER.keySet());
        nouns.addAll(GENERIC_NOUNS);
        nouns.sort((a, b) -> Integer.compare(b.length(), a.length()));
        StringBuilder sb = new StringBuilder("\\b(");
        for (int i = 0; i < nouns.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(toFlexNounRegex(nouns.get(i)));
        }
        sb.append(")\\b");
        NOUN_RE = Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    /**
     * Convert a space-separated noun phrase into a regex alternation that
     * matches the same phrase with EITHER a hyphen or whitespace between
     * each pair of words. Each word is regex-quoted; inter-word boundaries
     * use {@code [\s\-]+}.
     *
     * <p>Examples:
     * <ul>
     *   <li>"halt" → "\Qhalt\E"
     *   <li>"depth contraction" → "\Qdepth\E[\s\-]+\Qcontraction\E"
     *   <li>"order deletion event" →
     *       "\Qorder\E[\s\-]+\Qdeletion\E[\s\-]+\Qevent\E"
     * </ul>
     */
    private static String toFlexNounRegex(final String phrase) {
        String[] words = phrase.split("\\s+");
        if (words.length == 1) return Pattern.quote(words[0]);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) out.append("[\\s\\-]+");
            out.append(Pattern.quote(words[i]));
        }
        return out.toString();
    }

    /**
     * Map the prose noun-phrase match back to a key in {@link #NOUN_TO_SCORER}
     * by normalizing internal whitespace/hyphens to single spaces. This lets
     * "depth-contraction" find scorer_id liquidity_withdrawal even though the
     * key is "depth contraction".
     */
    private static String canonicalizeNoun(final String matched) {
        return matched.toLowerCase().replaceAll("[\\s\\-]+", " ").trim();
    }

    /** Ticker shape — same as {@link SynthesisVerifier#TICKER_RE}. */
    private static final Pattern TICKER_RE =
            Pattern.compile("\\b([A-Z]{2,5}(?:\\.[A-Z]{1,2}|[.=])?)\\b");

    /**
     * Digit OR cardinal-word numerals. The word-form union is built from
     * {@code GroundingVerifier.WORD_NUMBERS} keys (via reflection-free
     * duplication — keep in sync with that class).
     */
    private static final Pattern NUMBER_RE = Pattern.compile(
            "\\b("
                    + "\\d[\\d,_]*(?:\\.\\d+)?"
                    + "|zero|one|two|three|four|five|six|seven|eight|nine|ten"
                    + "|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen"
                    + "|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety"
                    + "|hundred|thousand|dozen"
                    + ")\\b",
            Pattern.CASE_INSENSITIVE);

    /** Verb-led attribution connectors: "{N} {noun} {connector} {subject}". */
    private static final Set<String> CONNECTORS = Set.of(
            "on", "for", "from", "across", "hit", "in", "by"
    );

    /** Window size: tokens to look back/forward when attributing. */
    private static final int LOOKBACK_TOKENS = 12;

    public AttributionVerifier() {}

    /**
     * Verify symbol-attributed count claims in the synthesis prose.
     *
     * @param prose             the LLM's synthesis paragraph
     * @param bySymbolByScorer  per-symbol per-scorer count map (truth source)
     * @param bySymbolTotal     per-symbol TOTAL event count, used for generic
     *                          event-noun claims that don't specify a scorer
     * @return result detailing each mismatch with the claim it came from
     */
    public Result verify(final String prose,
                         final Map<String, Map<String, Integer>> bySymbolByScorer,
                         final Map<String, Integer> bySymbolTotal) {
        List<String> mismatches = new ArrayList<>();
        List<AttributedClaim> claims = extractClaims(prose);

        for (AttributedClaim c : claims) {
            int actual;
            if (c.scorerId == null) {
                // Generic noun ("events" / "incidents") — check vs the symbol's
                // TOTAL event count.
                Integer t = bySymbolTotal.get(c.subject);
                actual = (t == null) ? -1 : t;
                if (actual == -1) {
                    mismatches.add("attributed claim \"" + c.subject + " ... "
                            + c.countWord + " " + c.nounMatch
                            + "\" — symbol not in today's narrations");
                    continue;
                }
            } else {
                Map<String, Integer> perScorer = bySymbolByScorer.get(c.subject);
                if (perScorer == null) {
                    mismatches.add("attributed claim \"" + c.subject + " ... "
                            + c.countWord + " " + c.nounMatch
                            + "\" — symbol not in today's narrations");
                    continue;
                }
                Integer a = perScorer.get(c.scorerId);
                actual = (a == null) ? 0 : a;
            }

            if (actual != c.count) {
                mismatches.add("attributed claim \"" + c.subject + " ... "
                        + c.countWord + " " + c.nounMatch + "\" → mapped to ("
                        + c.subject + ", " + c.scorerId + ") — actual count is "
                        + actual + ", not " + c.count);
            }
        }

        return new Result(mismatches.isEmpty(), mismatches, claims.size());
    }

    /**
     * Extract (subject, count, scorer-noun) claim tuples from prose. Public for
     * testing; not part of the verify contract.
     *
     * <p><b>Consumed-count tracking</b> (2026-05-28 fix for the "split between
     * A and B" false-positive): each count position in the prose can be
     * attributed to AT MOST ONE noun — the noun closest to it that finds it
     * via {@link #lookBackward}. Without this, prose like "16 events split
     * between post-cancel clusters and depth contractions" generates three
     * tuples ({@code (subj, 16, generic-events)}, {@code (subj, 16,
     * post_cancel_cluster)}, {@code (subj, 16, liquidity_withdrawal)}) — the
     * model meant "16 distributed between," not "16 of each." The closest
     * noun consumes the count; subsequent nouns scanning back must find a
     * different count or no claim fires.
     */
    public List<AttributedClaim> extractClaims(final String prose) {
        List<AttributedClaim> out = new ArrayList<>();
        if (prose == null || prose.isEmpty()) return out;

        // Walk the prose noun-by-noun in order. Track count positions consumed
        // by earlier nouns; subsequent nouns must skip past consumed counts.
        Set<Integer> consumedCountPositions = new HashSet<>();

        Matcher nm = NOUN_RE.matcher(prose);
        while (nm.find()) {
            int nounStart = nm.start();
            int nounEnd   = nm.end();
            String nounPhrase = nm.group(1);
            // canonicalize: lowercase + collapse [\s\-]+ → single space, so
            // "depth-contraction" maps to the "depth contraction" map key.
            String scorerId   = NOUN_TO_SCORER.get(canonicalizeNoun(nounPhrase));

            // Subject-led + possessive: look backward, skipping any count
            // positions already consumed by earlier nouns.
            CountAndSubject lookback = lookBackward(prose, nounStart, consumedCountPositions);
            if (lookback != null) {
                consumedCountPositions.add(lookback.countAbsolutePosition);
                out.add(new AttributedClaim(
                        lookback.subject, lookback.count, lookback.countWord,
                        scorerId, nounPhrase));
                continue;
            }

            // Verb-led: look forward for "{connector} {SUBJECT}".
            CountAndSubject lookforward = lookForward(prose, nounStart, nounEnd, consumedCountPositions);
            if (lookforward != null) {
                consumedCountPositions.add(lookforward.countAbsolutePosition);
                out.add(new AttributedClaim(
                        lookforward.subject, lookforward.count,
                        lookforward.countWord, scorerId, nounPhrase));
            }
        }
        return out;
    }

    /**
     * Look backward from {@code nounStart} for the nearest (count, subject)
     * pair within {@link #LOOKBACK_TOKENS} tokens.
     *
     * @param consumed already-consumed absolute count positions; skipped during
     *                 the search (the "split between" fix — each count
     *                 attributes to AT MOST one noun)
     */
    private CountAndSubject lookBackward(final String prose, final int nounStart,
                                         final Set<Integer> consumed) {
        int windowStart = Math.max(0, nounStart - 200);
        String before = prose.substring(windowStart, nounStart);
        // Tokenize the window with positions so we can compute absolute
        // offsets into the original prose for consumed-tracking.
        List<TokenAt> tokens = tokenizeWithPositions(before, windowStart);
        int n = tokens.size();
        if (n == 0) return null;

        // Walk backward through tokens. Find the nearest non-consumed NUMBER
        // first; then the nearest TICKER before it. Bounded by LOOKBACK_TOKENS.
        int countIdx = -1;
        int countAbsPos = -1;
        String countWord = null;
        Integer count = null;
        int limit = Math.max(0, n - LOOKBACK_TOKENS);
        for (int i = n - 1; i >= limit; i--) {
            TokenAt t = tokens.get(i);
            Integer c = parseCount(stripPunct(t.word));
            if (c != null && !consumed.contains(t.absStart)) {
                count = c;
                countWord = stripPunct(t.word);
                countIdx = i;
                countAbsPos = t.absStart;
                break;
            }
        }
        if (count == null) return null;

        // Now look for the nearest ticker BEFORE the count (subject-led)
        // OR immediately after the count toward the noun (rare; e.g.
        // "QQQ's six events" — possessive subject before count, handled
        // by going backward from countIdx).
        for (int i = countIdx - 1; i >= limit; i--) {
            String t = stripPunctButKeepDot(tokens.get(i).word);
            if (isTicker(t)) {
                return new CountAndSubject(t, count, countWord, countAbsPos);
            }
        }
        return null;
    }

    /**
     * Look forward from after the noun for the verb-led shape:
     * "{noun} {on|for|from|across} {SUBJECT}". Requires that a number appears
     * before the noun (within LOOKBACK_TOKENS) — pulled from the same window.
     */
    private CountAndSubject lookForward(final String prose,
                                         final int nounStart, final int nounEnd,
                                         final Set<Integer> consumed) {
        // First find the count BEFORE the noun (verb-led "{N} {noun} on {SUBJ}").
        int windowStart = Math.max(0, nounStart - 200);
        String before = prose.substring(windowStart, nounStart);
        List<TokenAt> bTokens = tokenizeWithPositions(before, windowStart);
        int bn = bTokens.size();
        Integer count = null;
        int countAbsPos = -1;
        String countWord = null;
        int blimit = Math.max(0, bn - LOOKBACK_TOKENS);
        for (int i = bn - 1; i >= blimit; i--) {
            TokenAt t = bTokens.get(i);
            Integer c = parseCount(stripPunct(t.word));
            if (c != null && !consumed.contains(t.absStart)) {
                count = c;
                countWord = stripPunct(t.word);
                countAbsPos = t.absStart;
                break;
            }
        }
        if (count == null) return null;

        // Now look forward for the connector + ticker.
        String after = prose.substring(nounEnd, Math.min(prose.length(), nounEnd + 60));
        String[] aTokens = after.split("\\s+");
        for (int i = 0; i < Math.min(aTokens.length - 1, 4); i++) {
            String maybeConn = stripPunct(aTokens[i]).toLowerCase();
            if (!CONNECTORS.contains(maybeConn)) continue;
            String maybeSubj = stripPunctButKeepDot(aTokens[i + 1]);
            if (isTicker(maybeSubj)) {
                return new CountAndSubject(maybeSubj, count, countWord, countAbsPos);
            }
        }
        return null;
    }

    /** Split text on whitespace, preserving absolute offsets into the source string. */
    private static List<TokenAt> tokenizeWithPositions(final String text, final int absoluteOffset) {
        List<TokenAt> out = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            // skip whitespace
            while (i < n && Character.isWhitespace(text.charAt(i))) i++;
            if (i >= n) break;
            int start = i;
            while (i < n && !Character.isWhitespace(text.charAt(i))) i++;
            out.add(new TokenAt(text.substring(start, i), absoluteOffset + start));
        }
        return out;
    }

    /** A token paired with its absolute start position in the original prose. */
    private record TokenAt(String word, int absStart) {}

    /** Strip leading/trailing punctuation (but keep '.' for tickers like BRK.B). */
    private static String stripPunct(final String token) {
        return token.replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "");
    }

    /**
     * Strip leading/trailing punctuation, including trailing sentence-end
     * periods, but preserve INTERNAL '.' so dotted tickers (BRK.B) survive.
     *
     * <p>"AAOG." (end-of-sentence) → "AAOG"; "BRK.B" (mid-sentence) → "BRK.B";
     * "BRK.B." (BRK.B at sentence end) → "BRK.B". The internal '.' is kept
     * because regex {@code [,;:!?.]+$} strips only contiguous trailing
     * punctuation — an internal '.' has alphanumeric chars on both sides and
     * isn't anchored at end.
     */
    private static String stripPunctButKeepDot(final String token) {
        return token.replaceAll("^[^A-Za-z0-9.=]+|[,;:!?.]+$", "");
    }

    /**
     * Parse a token as a count: either a digit literal or a cardinal word.
     * Returns null if it's neither.
     */
    private static Integer parseCount(final String token) {
        if (token == null || token.isEmpty()) return null;
        // Digit form
        if (token.matches("\\d[\\d,_]*")) {
            try {
                return Integer.parseInt(token.replace(",", "").replace("_", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        // Word form — use GroundingVerifier's static map via canonical
        // extraction. cardinalWordNumbersIn returns the canonical digit
        // string, e.g. "five" → "5".
        Set<String> wordNums = GroundingVerifier.cardinalWordNumbersIn(token);
        if (wordNums.size() == 1) {
            try {
                return Integer.parseInt(wordNums.iterator().next());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Is this token a valid ticker shape (subject-eligible)? */
    private static boolean isTicker(final String token) {
        if (token == null) return false;
        return TICKER_RE.matcher(token).matches();
    }

    /**
     * Result of an attribution verification pass.
     *
     * @param passed         true if no mismatches
     * @param mismatches     human-readable list of mismatched claims
     * @param claimsChecked  number of (subject, count, scorer) tuples extracted
     */
    public record Result(boolean passed, List<String> mismatches, int claimsChecked) {}

    /**
     * An extracted claim tuple. {@code scorerId} is null when the noun was
     * generic ("events" / "incidents") — caller routes to total-count lookup.
     *
     * @param subject     ticker symbol (the claim's attribution target)
     * @param count       canonical integer count claimed
     * @param countWord   the original prose word ("seven" / "20"), for the
     *                    mismatch message
     * @param scorerId    canonical scorer_id, or null for generic
     * @param nounMatch   the noun-phrase that matched, for the mismatch message
     */
    public record AttributedClaim(String subject, int count, String countWord,
                                   String scorerId, String nounMatch) {}

    /**
     * Backward/forward lookup helper. {@code countAbsolutePosition} is the
     * absolute character offset in the original prose where the count token
     * starts — used to mark counts as consumed in
     * {@link #extractClaims}.
     */
    private record CountAndSubject(String subject, int count, String countWord,
                                    int countAbsolutePosition) {}
}
