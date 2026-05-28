package com.longexposure.narration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AttributionVerifier} — the structural fix for
 * misattribution in SYNTHESIZE / AGGREGATE prose. Reproduces the bugs
 * observed in the 2026-05-28 audit + smoke-tests the extraction shapes
 * + false-positive guards.
 */
class AttributionVerifierTest {

    private static final Map<String, Integer> EMPTY_TOTALS = Map.of();

    /** The 05-13 reproducer: "TQQQ which recorded ten distinct order-deletion
     *  events" — actual liquidity_withdrawal count was 20. */
    @Test
    void misattributedWordFormCountIsFlagged() {
        AttributionVerifier v = new AttributionVerifier();
        AttributionVerifier.Result r = v.verify(
                "TQQQ which recorded ten distinct order-deletion events within the first hour.",
                Map.of("TQQQ", Map.of("liquidity_withdrawal", 20)),
                Map.of("TQQQ", 20));
        assertFalse(r.passed(), "10-vs-20 mismatch must be caught: " + r.mismatches());
        assertEquals(1, r.claimsChecked());
    }

    /** The 05-12 reproducer: "QQQ saw its order book compressed by six liquidity withdrawals"
     *  — actual count was 14. */
    @Test
    void misattributedDigitFormCountIsFlagged() {
        AttributionVerifier v = new AttributionVerifier();
        AttributionVerifier.Result r = v.verify(
                "QQQ saw its order book compressed by six liquidity withdrawals throughout the morning.",
                Map.of("QQQ", Map.of("liquidity_withdrawal", 14)),
                Map.of("QQQ", 14));
        assertFalse(r.passed(), "6-vs-14 mismatch must be caught");
    }

    /** A correctly-attributed count passes. */
    @Test
    void correctlyAttributedCountPasses() {
        AttributionVerifier v = new AttributionVerifier();
        AttributionVerifier.Result r = v.verify(
                "TQQQ recorded 20 liquidity withdrawals during the early session.",
                Map.of("TQQQ", Map.of("liquidity_withdrawal", 20)),
                Map.of("TQQQ", 20));
        assertTrue(r.passed(), "20 matches data — should pass: " + r.mismatches());
        assertEquals(1, r.claimsChecked());
    }

    /** Subject-led shape: "TQQQ ... N {scorer-noun}". */
    @Test
    void subjectLedShapeIsExtracted() {
        AttributionVerifier v = new AttributionVerifier();
        List<AttributionVerifier.AttributedClaim> claims = v.extractClaims(
                "TQQQ generated 18 layering events throughout the day.");
        assertEquals(1, claims.size());
        AttributionVerifier.AttributedClaim c = claims.get(0);
        assertEquals("TQQQ", c.subject());
        assertEquals(18, c.count());
        assertEquals("layering", c.scorerId());
    }

    /** Verb-led shape: "N {scorer-noun} on {SUBJECT}". */
    @Test
    void verbLedShapeIsExtracted() {
        AttributionVerifier v = new AttributionVerifier();
        List<AttributionVerifier.AttributedClaim> claims = v.extractClaims(
                "Four post-cancel clusters on IWM dominated the close.");
        assertEquals(1, claims.size());
        AttributionVerifier.AttributedClaim c = claims.get(0);
        assertEquals("IWM", c.subject());
        assertEquals(4, c.count());
        assertEquals("post_cancel_cluster", c.scorerId());
    }

    /** Word-form numerals are parsed. */
    @Test
    void wordFormCountsExtracted() {
        AttributionVerifier v = new AttributionVerifier();
        List<AttributionVerifier.AttributedClaim> claims = v.extractClaims(
                "QQQ exhibited seven distinct iceberg patterns through midday.");
        assertEquals(1, claims.size());
        assertEquals(7, claims.get(0).count());
        assertEquals("iceberg", claims.get(0).scorerId());
    }

    /** Multi-word scorer phrases ("iceberg pattern") match BEFORE the single-word
     *  ("pattern" generic noun) — longest-first ordering enforced. */
    @Test
    void multiWordScorerNounMatchesBeforeGeneric() {
        AttributionVerifier v = new AttributionVerifier();
        List<AttributionVerifier.AttributedClaim> claims = v.extractClaims(
                "AAPL produced 5 iceberg patterns over the session.");
        assertEquals(1, claims.size());
        assertEquals("iceberg", claims.get(0).scorerId(),
                "must match 'iceberg patterns' as iceberg, not generic 'patterns'");
    }

    /** Generic event nouns ("events" / "incidents") route through the
     *  per-symbol TOTAL, not a scorer-specific count. */
    @Test
    void genericNounChecksTotalCount() {
        AttributionVerifier v = new AttributionVerifier();
        // "AAPL with 18 incidents" — should check 18 vs AAPL's total events
        AttributionVerifier.Result rPass = v.verify(
                "AAPL recorded 18 incidents on the day.",
                Map.of("AAPL", Map.of("layering", 10, "sweep", 8)),
                Map.of("AAPL", 18));
        assertTrue(rPass.passed(), "18 == total events should pass: " + rPass.mismatches());

        AttributionVerifier.Result rFail = v.verify(
                "AAPL recorded 25 incidents on the day.",
                Map.of("AAPL", Map.of("layering", 10, "sweep", 8)),
                Map.of("AAPL", 18));
        assertFalse(rFail.passed(), "25 != total 18 should fail");
    }

    /** Unrelated prose without ticker+count pairings produces no claims. */
    @Test
    void unrelatedProseProducesNoClaims() {
        AttributionVerifier v = new AttributionVerifier();
        List<AttributionVerifier.AttributedClaim> claims = v.extractClaims(
                "The session was marked by intense early-session fragmentation and high-frequency activity.");
        assertEquals(0, claims.size(), "no symbols + counts → no claims: " + claims);
    }

    /** Multi-claim prose: extract each independently. */
    @Test
    void multipleClaimsInOneProse() {
        AttributionVerifier v = new AttributionVerifier();
        List<AttributionVerifier.AttributedClaim> claims = v.extractClaims(
                "TQQQ recorded 14 liquidity withdrawals; DGP saw 12 layering events.");
        assertEquals(2, claims.size());
        assertEquals("TQQQ", claims.get(0).subject());
        assertEquals(14, claims.get(0).count());
        assertEquals("liquidity_withdrawal", claims.get(0).scorerId());
        assertEquals("DGP", claims.get(1).subject());
        assertEquals(12, claims.get(1).count());
        assertEquals("layering", claims.get(1).scorerId());
    }

    /** Symbol claimed but not in today's narrations → mismatch. */
    @Test
    void unknownSymbolIsFlagged() {
        AttributionVerifier v = new AttributionVerifier();
        AttributionVerifier.Result r = v.verify(
                "TSLA had 5 sweeps during the open.",
                Map.of("AAPL", Map.of("sweep", 10)),
                Map.of("AAPL", 10));
        assertFalse(r.passed(), "TSLA absent from data → should flag");
    }

    /** Dotted ticker (BRK.B) is captured whole and looked up correctly. */
    @Test
    void dottedTickerHandled() {
        AttributionVerifier v = new AttributionVerifier();
        AttributionVerifier.Result r = v.verify(
                "BRK.B had 3 large blocks during the close.",
                Map.of("BRK.B", Map.of("large_trade", 3)),
                Map.of("BRK.B", 3));
        assertTrue(r.passed(), "BRK.B should match BRK.B: " + r.mismatches());
    }

    /** When prose lacks any scorer-noun, nothing fires (no false positives). */
    @Test
    void plainProseTriggersNothing() {
        AttributionVerifier v = new AttributionVerifier();
        List<AttributionVerifier.AttributedClaim> claims = v.extractClaims(
                "TQQQ traded heavily with 18 distinct price movements over $5 in basis points.");
        // "price movements" and "basis points" are not scorer nouns;
        // 18 is not attributed to any scorer-noun.
        assertEquals(0, claims.size(),
                "non-scorer noun phrases should not trigger claims: " + claims);
    }

    /** "Trading halt(s)" variants are detected. */
    @Test
    void haltVariantsDetected() {
        AttributionVerifier v = new AttributionVerifier();
        assertEquals(1, v.extractClaims("AAOG entered 1 trading halt at the open.").size());
        assertEquals(1, v.extractClaims("Seven halts disrupted pre-market on AAOG.").size());
    }

    /** Numbers > 10 in word form are parsed (covers "twenty events"). */
    @Test
    void teensAndDecadesParsed() {
        AttributionVerifier v = new AttributionVerifier();
        AttributionVerifier.Result r = v.verify(
                "TQQQ exhibited twenty layering events.",
                Map.of("TQQQ", Map.of("layering", 20)),
                Map.of("TQQQ", 20));
        assertTrue(r.passed(), "twenty=20 should match: " + r.mismatches());
    }
}
