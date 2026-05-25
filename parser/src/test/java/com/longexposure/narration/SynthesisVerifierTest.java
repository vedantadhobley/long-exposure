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
}
