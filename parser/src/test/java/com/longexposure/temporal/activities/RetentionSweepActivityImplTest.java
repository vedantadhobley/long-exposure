package com.longexposure.temporal.activities;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link RetentionSweepActivityImpl#weekBoundary} — the
 * week-aligned drop boundary. Policy: keep the current (possibly partial)
 * week plus {@code retainWeeks} completed weeks; never dip below 2 full
 * weeks with {@code retainWeeks=2}.
 */
class RetentionSweepActivityImplTest {

    // 2026-05-25 is a Monday; the week runs Mon 05-25 .. Sun 05-31.

    @Test
    void mondayKeepsCurrentPlusTwoCompletedWeeks() {
        // retainWeeks=2 from Monday 05-25 → drop before Monday 05-11.
        // Held: weeks of 05-11, 05-18, and current 05-25 = 2 full + current.
        assertEquals(LocalDate.of(2026, 5, 11),
                RetentionSweepActivityImpl.weekBoundary(LocalDate.of(2026, 5, 25), 2));
    }

    @Test
    void boundaryIsStableMidWeek() {
        // Wed 05-27 and Sat 05-30 are in the same week as Mon 05-25, so the
        // boundary doesn't move — the 2-weeks-ago week is NOT dropped until
        // the current week closes.
        assertEquals(LocalDate.of(2026, 5, 11),
                RetentionSweepActivityImpl.weekBoundary(LocalDate.of(2026, 5, 27), 2));
        assertEquals(LocalDate.of(2026, 5, 11),
                RetentionSweepActivityImpl.weekBoundary(LocalDate.of(2026, 5, 30), 2));
    }

    @Test
    void boundaryAdvancesWhenNewWeekBegins() {
        // Once the current week rolls to Mon 06-01, the boundary advances to
        // Mon 05-18 — the now-3-weeks-ago week of 05-11 rolls off, leaving
        // 05-18 + 05-25 (2 full) + current.
        assertEquals(LocalDate.of(2026, 5, 18),
                RetentionSweepActivityImpl.weekBoundary(LocalDate.of(2026, 6, 1), 2));
    }

    @Test
    void retainWeeksZeroKeepsOnlyCurrentWeek() {
        assertEquals(LocalDate.of(2026, 5, 25),
                RetentionSweepActivityImpl.weekBoundary(LocalDate.of(2026, 5, 27), 0));
    }

    @Test
    void extendsCleanlyToFourWeeks() {
        // The policy scales: retainWeeks=4 from Mon 05-25 → drop before 04-27.
        assertEquals(LocalDate.of(2026, 4, 27),
                RetentionSweepActivityImpl.weekBoundary(LocalDate.of(2026, 5, 25), 4));
    }
}
