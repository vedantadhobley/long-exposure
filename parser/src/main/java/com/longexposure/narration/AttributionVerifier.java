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

    /**
     * Words that indicate a count attributes to the SUM of multiple listed
     * subjects ("DGP, TQQQ, and QQQ collectively generated 49 events").
     * Without this signal a count near multiple tickers would mis-attribute
     * to just the nearest one; the {@link #extractMultiSymbolClaims} pass
     * uses these triggers to identify list-style attribution.
     */
    private static final Set<String> MULTI_SYMBOL_TRIGGERS = Set.of(
            "collectively", "together", "combined", "jointly"
    );

    /** Window size: tokens to look back/forward when attributing. */
    private static final int LOOKBACK_TOKENS = 12;

    /** Max tokens forward of a multi-symbol trigger to find count + noun. */
    private static final int MULTI_LOOKFORWARD_TOKENS = 20;

    /** Max tokens before a multi-symbol trigger to find the ticker list. */
    private static final int MULTI_LOOKBACKWARD_TOKENS = 15;

    public AttributionVerifier() {}

    /**
     * Verify symbol-attributed count claims in the synthesis prose.
     *
     * <p>Runs TWO passes:
     * <ol>
     *   <li>Multi-symbol attribution: "X, Y, and Z collectively had N events"
     *       — count compared against the SUM of per-symbol counts.
     *   <li>Single-symbol attribution: "X had N events" — count compared
     *       against the per-symbol count.
     * </ol>
     * Multi-symbol runs first so its count positions are consumed before the
     * single-symbol pass — prevents "DGP, TQQQ, and QQQ collectively 49"
     * from also generating (TQQQ, 49, generic) as a single-symbol claim.
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

        // Pass 1: multi-symbol attribution (collectively / together / combined / jointly)
        Set<Integer> consumedCountPositions = new HashSet<>();
        List<MultiSymbolClaim> multiClaims = extractMultiSymbolClaims(prose, consumedCountPositions);

        for (MultiSymbolClaim mc : multiClaims) {
            int actualSum = 0;
            List<String> unknownSymbols = new ArrayList<>();
            for (String sym : mc.subjects) {
                if (mc.scorerId == null) {
                    Integer t = bySymbolTotal.get(sym);
                    if (t == null) unknownSymbols.add(sym); else actualSum += t;
                } else {
                    Map<String, Integer> perScorer = bySymbolByScorer.get(sym);
                    if (perScorer == null) {
                        unknownSymbols.add(sym);
                    } else {
                        Integer a = perScorer.get(mc.scorerId);
                        actualSum += (a == null) ? 0 : a;
                    }
                }
            }
            if (!unknownSymbols.isEmpty()) {
                mismatches.add("multi-symbol attributed claim \"" + String.join(", ", mc.subjects)
                        + " ... " + mc.countWord + " " + mc.nounMatch
                        + "\" — symbols not in today's narrations: " + unknownSymbols);
                continue;
            }
            if (actualSum != mc.count) {
                mismatches.add("multi-symbol attributed claim \""
                        + String.join(", ", mc.subjects) + " ... "
                        + mc.countWord + " " + mc.nounMatch
                        + "\" → expected sum of (" + String.join(", ", mc.subjects)
                        + ") per " + (mc.scorerId == null ? "events" : mc.scorerId)
                        + " — actual sum is " + actualSum + ", not " + mc.count);
            }
        }

        // Pass 2: single-symbol attribution, with multi-pass consumed counts
        // already excluded.
        List<AttributedClaim> claims = extractClaimsWithConsumed(prose, consumedCountPositions);

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

        return new Result(mismatches.isEmpty(), mismatches, claims.size() + multiClaims.size());
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
        return extractClaimsWithConsumed(prose, new HashSet<>());
    }

    /**
     * Same as {@link #extractClaims(String)} but allows the caller to seed
     * the consumed-count-positions set — used internally by {@link #verify}
     * to exclude counts already attributed by the multi-symbol pass.
     */
    List<AttributedClaim> extractClaimsWithConsumed(final String prose,
                                                     final Set<Integer> consumedCountPositions) {
        List<AttributedClaim> out = new ArrayList<>();
        if (prose == null || prose.isEmpty()) return out;

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
     * Extract multi-symbol attribution claims — prose patterns like
     * "X, Y, and Z collectively generated N events" where the count
     * attributes to the SUM of the listed subjects, not to any single one.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Scan prose for each MULTI_SYMBOL_TRIGGERS word.
     *   <li>Look backward up to MULTI_LOOKBACKWARD_TOKENS tokens for a
     *       comma-and-conjunction sequence of ≥2 tickers.
     *   <li>Look forward up to MULTI_LOOKFORWARD_TOKENS tokens for a
     *       count + scorer-noun.
     *   <li>Mark the count position as consumed (added to the caller's set)
     *       so the subsequent single-symbol pass doesn't re-claim it.
     * </ol>
     *
     * <p>Public for testability; not part of the verify contract.
     */
    public List<MultiSymbolClaim> extractMultiSymbolClaims(
            final String prose, final Set<Integer> consumedCountPositions) {
        List<MultiSymbolClaim> out = new ArrayList<>();
        if (prose == null || prose.isEmpty()) return out;

        // Word-by-word scan to find trigger positions. Track absolute offsets
        // so we can mark counts as consumed for the single-symbol pass.
        List<TokenAt> tokens = tokenizeWithPositions(prose, 0);
        for (int i = 0; i < tokens.size(); i++) {
            String w = stripPunct(tokens.get(i).word).toLowerCase();
            if (!MULTI_SYMBOL_TRIGGERS.contains(w)) continue;

            // Look backward for ticker list within MULTI_LOOKBACKWARD_TOKENS.
            List<String> subjects = collectTickerListBefore(tokens, i);
            if (subjects.size() < 2) continue;   // need ≥2 tickers for multi-symbol

            // Look forward for count + scorer-noun within MULTI_LOOKFORWARD_TOKENS.
            CountAndNoun cn = findCountAndNounAfter(tokens, i, prose);
            if (cn == null) continue;

            consumedCountPositions.add(cn.countAbsolutePosition);
            out.add(new MultiSymbolClaim(
                    subjects, cn.count, cn.countWord, cn.scorerId, cn.nounMatch));
        }
        return out;
    }

    /**
     * Collect a ticker list immediately before {@code triggerIdx}, walking
     * backward through "ticker [, ticker]* [, and] ticker" structure. Returns
     * an empty list if the look-back window doesn't reveal ≥2 tickers in a
     * plausibly contiguous list.
     */
    private List<String> collectTickerListBefore(final List<TokenAt> tokens, final int triggerIdx) {
        List<String> reversed = new ArrayList<>();
        int limit = Math.max(0, triggerIdx - MULTI_LOOKBACKWARD_TOKENS);
        // The token immediately before the trigger should be either a ticker
        // (last item in the list, e.g. "QQQ collectively") or a generic word
        // like "leveraged" / "ETFs". We allow up to 2 non-ticker tokens
        // between trigger and last ticker (e.g. "QQQ each collectively").
        int lastTickerIdx = -1;
        for (int j = triggerIdx - 1; j >= limit && (triggerIdx - 1 - j) <= 2; j--) {
            String t = stripPunctButKeepDot(tokens.get(j).word);
            if (isTicker(t)) { lastTickerIdx = j; break; }
        }
        if (lastTickerIdx < 0) return List.of();

        // Now walk backward from lastTickerIdx, collecting tickers separated
        // by comma OR the conjunction "and". Stop at the first non-(ticker,
        // and, ",")-shaped token.
        reversed.add(stripPunctButKeepDot(tokens.get(lastTickerIdx).word));
        boolean expectSeparator = true;   // next token back should be "," or "and"
        for (int j = lastTickerIdx - 1; j >= limit; j--) {
            String raw = tokens.get(j).word;
            String t = stripPunctButKeepDot(raw);
            String lower = stripPunct(raw).toLowerCase();
            boolean rawHasComma = raw.contains(",");
            if (expectSeparator) {
                // Accept "and" / "&" as conjunctions; accept comma in the raw token.
                if (lower.equals("and") || lower.equals("&") || rawHasComma) {
                    expectSeparator = false;
                    // If the token itself contains a comma but isn't only punctuation,
                    // e.g. "TQQQ," — we already stripped it; the previous iteration
                    // captured the ticker. Continue.
                    if (rawHasComma && isTicker(t)) {
                        reversed.add(t);
                        expectSeparator = true;
                    }
                    continue;
                }
                // No separator → end of list.
                break;
            } else {
                if (isTicker(t)) {
                    reversed.add(t);
                    expectSeparator = true;
                } else {
                    break;
                }
            }
        }

        // Reverse to forward order.
        List<String> out = new ArrayList<>(reversed.size());
        for (int k = reversed.size() - 1; k >= 0; k--) out.add(reversed.get(k));
        return out;
    }

    /**
     * Look forward of {@code triggerIdx} for a count and a scorer-noun (or
     * generic event noun). Returns the (count, noun, scorerId) tuple if
     * found, else null.
     */
    private CountAndNoun findCountAndNounAfter(final List<TokenAt> tokens, final int triggerIdx,
                                                final String prose) {
        int limit = Math.min(tokens.size(), triggerIdx + 1 + MULTI_LOOKFORWARD_TOKENS);
        // Find the first count token forward.
        int countIdx = -1;
        Integer count = null;
        String countWord = null;
        int countAbsPos = -1;
        for (int j = triggerIdx + 1; j < limit; j++) {
            Integer c = parseCount(stripPunct(tokens.get(j).word));
            if (c != null) {
                count = c;
                countWord = stripPunct(tokens.get(j).word);
                countIdx = j;
                countAbsPos = tokens.get(j).absStart;
                break;
            }
        }
        if (count == null) return null;

        // Then find the next noun forward of the count.
        // Use NOUN_RE on the slice of prose after the count's end position to
        // catch multi-word noun phrases.
        int countEndAbs = countAbsPos + countWord.length();
        int sliceEnd = Math.min(prose.length(), countEndAbs + 80);
        String slice = prose.substring(countEndAbs, sliceEnd);
        Matcher nm = NOUN_RE.matcher(slice);
        if (!nm.find()) return null;
        String nounPhrase = nm.group(1);
        String scorerId = NOUN_TO_SCORER.get(canonicalizeNoun(nounPhrase));
        return new CountAndNoun(count, countWord, countAbsPos, scorerId, nounPhrase);
    }

    /** Forward-lookup helper for multi-symbol claims. */
    private record CountAndNoun(int count, String countWord, int countAbsolutePosition,
                                 String scorerId, String nounMatch) {}

    /**
     * A claim that attributes a count to the SUM of multiple listed subjects.
     * Mirrors {@link AttributedClaim} but with a list of subjects instead of
     * a single one. {@code scorerId} is null for generic event nouns.
     */
    public record MultiSymbolClaim(List<String> subjects, int count, String countWord,
                                    String scorerId, String nounMatch) {}

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
     * Extract every scorer_id mentioned in the prose via the scorer-noun
     * vocabulary. "TQQQ saw layering activity then a post-cancel cluster"
     * returns {layering, post_cancel_cluster}. Generic event nouns
     * ("events", "incidents") don't map to a scorer and are omitted.
     *
     * <p>Public for reuse by other verifiers (e.g. INTERPRET / DESCRIBE) that
     * need to detect pattern-name mislabels: if prose names a scorer not in
     * (event's own scorer_id ∪ co_occurring scorer_ids), the model has
     * misclassified the event type. Observed 2026-05-28 audit: AKBA
     * layering INTERPRET ended "…leaving the liquidity withdrawal in
     * isolation" — wrong scorer-noun for a layering event.
     */
    public static Set<String> extractScorerNounIds(final String prose) {
        Set<String> out = new HashSet<>();
        if (prose == null || prose.isEmpty()) return out;
        Matcher m = NOUN_RE.matcher(prose);
        while (m.find()) {
            String scorerId = NOUN_TO_SCORER.get(canonicalizeNoun(m.group(1)));
            if (scorerId != null) out.add(scorerId);
        }
        return out;
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
