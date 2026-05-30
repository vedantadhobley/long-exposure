package com.longexposure.narration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScorerPromptsTest {

    @Test
    void allRegisteredScorersResolve() {
        // Mirror of EventScorerRegistry.ALL.
        for (String id : new String[]{
                "halt", "large_trade", "sweep", "post_cancel_cluster",
                "layering", "iceberg", "liquidity_withdrawal",
                "volume_deviation", "time_in_book_drift"}) {
            ScorerPrompts.ScorerPrompt p = ScorerPrompts.forScorer(id);
            assertNotNull(p, "no prompt for " + id);
            assertNotNull(p.eventNoun(), "no eventNoun for " + id);
            assertTrue(p.scorerSection().length() > 100,
                    "scorerSection too short for " + id + ": " + p.scorerSection().length());
        }
    }

    @Test
    void unknownScorerThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ScorerPrompts.forScorer("nonexistent_scorer"));
    }

    @Test
    void eventNounsAreDistinct() {
        // Each scorer must produce a distinct event noun phrase so the
        // model can't confuse types based on the noun.
        String[] ids = {
                "halt", "large_trade", "sweep", "post_cancel_cluster",
                "layering", "iceberg", "liquidity_withdrawal",
                "volume_deviation", "time_in_book_drift"};
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String id : ids) {
            String noun = ScorerPrompts.forScorer(id).eventNoun();
            assertTrue(seen.add(noun), "duplicate event noun: " + noun);
        }
        assertEquals(ids.length, seen.size());
    }

    @Test
    void commonPreambleEnforcesClassLabelRule() {
        // The structural fix for the v10/v11 jargon leakage: COMMON_PREAMBLE
        // must instruct the model NOT to include the underlying numeric
        // value as a parenthetical when a class label is present.
        assertTrue(ScorerPrompts.COMMON_PREAMBLE.contains("Do NOT include the underlying numeric value"),
                "COMMON_PREAMBLE missing the class-label parenthetical rule");
        assertTrue(ScorerPrompts.COMMON_PREAMBLE.contains("(Fano")
                        || ScorerPrompts.COMMON_PREAMBLE.contains("burstiness_fano"),
                "COMMON_PREAMBLE should reference burstiness_fano as an example");
    }

    @Test
    void commonPreambleEnforcesCoOccurringLimit() {
        assertTrue(ScorerPrompts.COMMON_PREAMBLE.contains("AT MOST 3 co_occurring"),
                "COMMON_PREAMBLE missing the co_occurring entry limit");
    }

    @Test
    void haltPromptMentionsPhaseSpanLabel() {
        // The Phase 7 halt_phase_span_label fix must survive in the
        // per-scorer prompt.
        String halt = ScorerPrompts.forScorer("halt").scorerSection();
        assertTrue(halt.contains("halt_phase_span_label"),
                "halt prompt must reference halt_phase_span_label");
        assertTrue(halt.contains("VERBATIM"),
                "halt prompt must instruct using halt_phase_span_label verbatim");
    }

    @Test
    void icebergPromptHandlesRefillCadence() {
        String iceberg = ScorerPrompts.forScorer("iceberg").scorerSection();
        assertTrue(iceberg.contains("refill_cadence_class"),
                "iceberg prompt must reference refill_cadence_class");
        assertTrue(iceberg.contains("LABEL ALONE") || iceberg.contains("Do NOT include refill_cadence_cv"),
                "iceberg prompt must forbid CV parenthetical");
    }

    @Test
    void volumeDeviationAvoidsSigmaRendering() {
        String vd = ScorerPrompts.forScorer("volume_deviation").scorerSection();
        assertTrue(vd.contains("DO NOT render robust_z as \"sigma\"")
                        || vd.contains("not \"sigma\""),
                "volume_deviation must forbid robust_z-as-sigma rendering");
    }
}
