package com.longexposure.narration;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SynthesisVerifier} — focused on the dotted class-share
 * ticker handling (the BRK.B false-positive found in the 2026-05-18→22
 * backfill audit) and shared by the weekly AGGREGATE stage.
 */
class SynthesisVerifierTest {

    private static final String EMPTY_HAYSTACK = "";

    /** The regression: "BRK.B" in prose must match the symbol "BRK.B", not flag. */
    @Test
    void dottedClassShareTickerIsNotFlagged() {
        SynthesisVerifier v = new SynthesisVerifier();
        SynthesisVerifier.Result r = v.verify(
                "Large blocks anchored moves in BRK.B through the session.",
                Set.of("BRK.B"), EMPTY_HAYSTACK);
        assertTrue(r.passed(), "BRK.B should match symbol BRK.B, not flag: " + r.mismatches());
    }

    /** "BRK" shorthand for a "BRK.B" symbol is accepted (pre-dot prefix). */
    @Test
    void preDotShorthandIsAccepted() {
        SynthesisVerifier v = new SynthesisVerifier();
        SynthesisVerifier.Result r = v.verify(
                "BRK saw repeated large trades.",
                Set.of("BRK.B"), EMPTY_HAYSTACK);
        assertTrue(r.passed(), "BRK should match symbol BRK.B via pre-dot prefix: " + r.mismatches());
    }

    /** A genuinely-absent ticker is still flagged (the check still works). */
    @Test
    void fabricatedTickerIsFlagged() {
        SynthesisVerifier v = new SynthesisVerifier();
        SynthesisVerifier.Result r = v.verify(
                "TSLA had a rough day.",
                Set.of("AAPL", "BRK.B"), EMPTY_HAYSTACK);
        assertFalse(r.passed(), "TSLA not in symbol set should be flagged");
    }

    /** IEX suffix symbols ("OTAI=") accepted whether prose keeps or drops the suffix. */
    @Test
    void suffixSymbolAcceptedEitherForm() {
        SynthesisVerifier v = new SynthesisVerifier();
        assertTrue(v.verify("OTAI= led pre-market.", Set.of("OTAI="), EMPTY_HAYSTACK).passed());
        assertTrue(v.verify("OTAI led pre-market.", Set.of("OTAI="), EMPTY_HAYSTACK).passed());
    }

    /** Common finance acronyms are not treated as fabricated tickers. */
    @Test
    void acronymsAreNotTickers() {
        SynthesisVerifier v = new SynthesisVerifier();
        SynthesisVerifier.Result r = v.verify(
                "ETFs saw LULD halts; the NBBO widened on AAPL.",
                Set.of("AAPL"), EMPTY_HAYSTACK);
        assertTrue(r.passed(), "ETF/LULD/NBBO are acronyms, not tickers: " + r.mismatches());
    }

    /** extractTickers (used by AGGREGATE to build the week's universe) captures dotted tickers whole. */
    @Test
    void extractTickersCapturesDottedWhole() {
        Set<String> t = SynthesisVerifier.extractTickers(
                "BRK.B and AAPL traded heavily; ETF flows were light.");
        assertTrue(t.contains("BRK.B"), "should capture BRK.B whole: " + t);
        assertTrue(t.contains("AAPL"), "should capture AAPL: " + t);
        assertFalse(t.contains("ETF"), "ETF is an acronym, excluded: " + t);
        assertFalse(t.contains("BRK."), "must not split BRK.B into BRK.: " + t);
    }

    // ─── Cardinal word-form numerals (2026-05-28 fix) ───────────────────────

    /** A grounded word-form count ("six halts" with 6 in haystack) passes. */
    @Test
    void groundedWordFormCountPasses() {
        SynthesisVerifier v = new SynthesisVerifier();
        SynthesisVerifier.Result r = v.verify(
                "AAPL absorbed six pre-market halts.",
                Set.of("AAPL"),
                "{\"by_scorer\":{\"halt\":6}}");
        assertTrue(r.passed(), "word-form 'six' should ground against haystack '6': " + r.mismatches());
    }

    /** An ungrounded word-form count is flagged (the original bug). */
    @Test
    void ungroundedWordFormCountIsFlagged() {
        SynthesisVerifier v = new SynthesisVerifier();
        SynthesisVerifier.Result r = v.verify(
                // 05-12 reproducer: synthesis said "six liquidity withdrawals"
                // when 14 actually occurred.
                "QQQ saw its order book compressed by six liquidity withdrawals.",
                Set.of("QQQ"),
                "{\"by_scorer\":{\"liquidity_withdrawal\":14}}");
        assertFalse(r.passed(), "ungrounded 'six' (haystack only has 14) must be flagged");
        assertTrue(r.mismatches().stream().anyMatch(m -> m.contains("word-form \"6\"")),
                "mismatch should cite the canonical form: " + r.mismatches());
    }

    /** Word-form on both sides matches: "eight events" prose vs "eight events" haystack. */
    @Test
    void wordFormOnBothSidesMatches() {
        SynthesisVerifier v = new SynthesisVerifier();
        SynthesisVerifier.Result r = v.verify(
                "ten events anchored the day.",
                Set.of(),
                "An interpretation mentioned ten events on the day.");
        assertTrue(r.passed(), "prose 'ten' should ground against haystack 'ten': " + r.mismatches());
    }

    /** Word "two" in prose grounds against digit "2" in haystack (the cross-form case). */
    @Test
    void crossFormWordToDigitMatches() {
        SynthesisVerifier v = new SynthesisVerifier();
        SynthesisVerifier.Result r = v.verify(
                "Two co-occurring layering events appeared.",
                Set.of(),
                "{\"layering\":{\"count\":2}}");
        assertTrue(r.passed(), "prose 'Two' should match haystack '2': " + r.mismatches());
    }

    /** Decade words ("twenty", "thirty") work too. */
    @Test
    void decadeWordsHandled() {
        SynthesisVerifier v = new SynthesisVerifier();
        // grounded
        assertTrue(v.verify("Thirty events fired.", Set.of(), "{\"count\":30}").passed());
        // ungrounded
        assertFalse(v.verify("Thirty events fired.", Set.of(), "{\"count\":25}").passed());
    }

    /** "hundred" / "thousand" handled. */
    @Test
    void hundredAndThousandHandled() {
        SynthesisVerifier v = new SynthesisVerifier();
        assertTrue(v.verify("over one hundred orders posted.", Set.of(),
                "{\"orders\":100}").passed());
    }

    /** Word boundaries — "ten" in "often" / "listen" must NOT match. */
    @Test
    void wordBoundaryIsolatesNumerals() {
        SynthesisVerifier v = new SynthesisVerifier();
        SynthesisVerifier.Result r = v.verify(
                "Often heard, listen carefully to the rotten outcome.",
                Set.of(),
                "{}");
        // No numerals to check — should pass since regex won't match "ten" inside "often"/"listen"/"rotten".
        assertTrue(r.passed(), "must not extract 'ten' from inside 'often'/'listen'/'rotten': " + r.mismatches());
    }

    /** No-fills phrasing ("zero fills") grounds against digit "0". */
    @Test
    void zeroWordFormHandled() {
        SynthesisVerifier v = new SynthesisVerifier();
        assertTrue(v.verify("Zero fills against the posted orders.", Set.of(),
                "{\"fills\":0}").passed());
    }
}
