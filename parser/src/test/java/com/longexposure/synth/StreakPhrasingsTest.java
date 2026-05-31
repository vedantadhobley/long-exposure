package com.longexposure.synth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StreakPhrasingsTest {

    @Test
    void zeroPriorsForbidsAllStreakPhrasing() {
        String out = StreakPhrasings.allowedStreakPhrasings(0, "week");
        assertTrue(out.contains("ALLOWED STREAK PHRASING: NONE"));
        assertTrue(out.contains("OMIT all such phrasing entirely"));
        // No specific ordinal should be listed
        assertFalse(out.contains("\"a second consecutive"));
    }

    @Test
    void onePriorAllowsSecondOnly() {
        String out = StreakPhrasings.allowedStreakPhrasings(1, "week");
        assertTrue(out.contains("\"a second consecutive week\""));
        assertFalse(out.contains("\"a third consecutive week\""));
        assertTrue(out.contains("FORBIDDEN: any ordinal at or beyond \"third consecutive week\""));
    }

    @Test
    void twoPriorsAllowsSecondAndThird() {
        String out = StreakPhrasings.allowedStreakPhrasings(2, "week");
        assertTrue(out.contains("\"a second consecutive week\""));
        assertTrue(out.contains("\"a third consecutive week\""));
        assertFalse(out.contains("\"a fourth consecutive week\""));
        assertTrue(out.contains("\"fourth consecutive week\""));  // appears in forbidden line
    }

    @Test
    void threePriorsAllowsThrough4th() {
        String out = StreakPhrasings.allowedStreakPhrasings(3, "week");
        assertTrue(out.contains("\"a second consecutive week\""));
        assertTrue(out.contains("\"a third consecutive week\""));
        assertTrue(out.contains("\"a fourth consecutive week\""));
        assertFalse(out.contains("\"a fifth consecutive week\""));
    }

    @Test
    void worksForQuarterUnit() {
        String out = StreakPhrasings.allowedStreakPhrasings(1, "quarter");
        assertTrue(out.contains("\"a second consecutive quarter\""));
        assertTrue(out.contains("quarter-over-quarter") || out.contains("consecutive quarter"));
    }

    @Test
    void worksForYearUnit() {
        String out = StreakPhrasings.allowedStreakPhrasings(0, "year");
        assertTrue(out.contains("year-over-year"));
        assertTrue(out.contains("consecutive year"));
    }

    @Test
    void includesSafeFallback() {
        String out = StreakPhrasings.allowedStreakPhrasings(2, "week");
        assertTrue(out.contains("continuing from last week"));
    }

    @Test
    void capsAtOrdinalTable() {
        // priorCount = 20 should cap at the table size (14)
        String out = StreakPhrasings.allowedStreakPhrasings(20, "week");
        assertTrue(out.contains("\"a fourteenth consecutive week\""));
        // No 15th
        assertFalse(out.contains("fifteenth"));
    }
}
