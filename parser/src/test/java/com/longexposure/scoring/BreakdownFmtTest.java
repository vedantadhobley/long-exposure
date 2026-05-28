package com.longexposure.scoring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Focused tests for {@link BreakdownFmt#durationSecHumanized} — the
 * pluralization-safe formatter added 2026-05-28 to eliminate the
 * "1 seconds" failure mode in liquidity_withdrawal / iceberg prose.
 */
class BreakdownFmtTest {

    @Test
    void singularSecond() {
        assertEquals("1 second", BreakdownFmt.durationSecHumanized(1));
    }

    @Test
    void pluralSeconds() {
        assertEquals("2 seconds", BreakdownFmt.durationSecHumanized(2));
        assertEquals("45 seconds", BreakdownFmt.durationSecHumanized(45));
    }

    @Test
    void singularMinute() {
        assertEquals("1 minute", BreakdownFmt.durationSecHumanized(60));
        assertEquals("1 minute 1 second", BreakdownFmt.durationSecHumanized(61));
    }

    @Test
    void pluralMinutes() {
        assertEquals("5 minutes", BreakdownFmt.durationSecHumanized(300));
        assertEquals("5 minutes 30 seconds", BreakdownFmt.durationSecHumanized(330));
    }

    @Test
    void mixedHourMinute() {
        assertEquals("1 hour", BreakdownFmt.durationSecHumanized(3600));
        assertEquals("1 hour 1 minute", BreakdownFmt.durationSecHumanized(3660));
        assertEquals("2 hours 15 minutes", BreakdownFmt.durationSecHumanized(2 * 3600 + 15 * 60));
        // Boundary: exactly 1 hour 1 second — should drop the seconds (no
        // second-level precision when we're in hour territory).
        assertEquals("1 hour", BreakdownFmt.durationSecHumanized(3601));
    }

    @Test
    void zeroAndNegative() {
        assertEquals("0 seconds", BreakdownFmt.durationSecHumanized(0));
        assertEquals("unknown duration", BreakdownFmt.durationSecHumanized(-1));
    }

    // ─── haltPhaseSpan — Phase 7 compound phase-label fix ──────────────────

    @Test
    void phaseSpanDifferentPhases() {
        // 07:00 ET pre-market start, 13:30 ET midday end (different phases)
        java.time.Instant preMarket = java.time.LocalDateTime.of(2026, 5, 12, 11, 0)
                .atZone(java.time.ZoneOffset.UTC).toInstant();   // 07:00 ET
        java.time.Instant midday = java.time.LocalDateTime.of(2026, 5, 12, 17, 30)
                .atZone(java.time.ZoneOffset.UTC).toInstant();   // 13:30 ET
        assertEquals("starting in pre-market trading and resuming around midday",
                BreakdownFmt.haltPhaseSpan(preMarket, midday));
    }

    @Test
    void phaseSpanSamePhase() {
        // Both in midday — should be "lasting through midday"
        java.time.Instant t1 = java.time.LocalDateTime.of(2026, 5, 12, 16, 30)
                .atZone(java.time.ZoneOffset.UTC).toInstant();   // 12:30 ET (midday)
        java.time.Instant t2 = java.time.LocalDateTime.of(2026, 5, 12, 17, 30)
                .atZone(java.time.ZoneOffset.UTC).toInstant();   // 13:30 ET (still midday)
        assertEquals("lasting through midday",
                BreakdownFmt.haltPhaseSpan(t1, t2));
    }

    @Test
    void phaseSpanUnbounded() {
        java.time.Instant preMarket = java.time.LocalDateTime.of(2026, 5, 12, 11, 0)
                .atZone(java.time.ZoneOffset.UTC).toInstant();   // 07:00 ET
        assertEquals("starting in pre-market trading with no resume in the window",
                BreakdownFmt.haltPhaseSpan(preMarket, null));
    }
}
