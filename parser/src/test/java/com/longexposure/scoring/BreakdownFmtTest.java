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
}
