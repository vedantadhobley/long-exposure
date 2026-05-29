package com.longexposure.temporal.workflows;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PipelineWorkflowImpl#computeCascadeScope} — the pure
 * function that maps a list of dates to the set of touched
 * (week_start, quarter_start, year_start) periods for the rollup cascade.
 */
class PipelineWorkflowCascadeTest {

    /** Single date → one week, one quarter, one year. */
    @Test
    void singleDate() {
        PipelineWorkflowImpl.CascadeScope scope =
                PipelineWorkflowImpl.computeCascadeScope(List.of(LocalDate.of(2026, 5, 13)));
        assertEquals(1, scope.weekStarts().size());
        assertEquals(LocalDate.of(2026, 5, 11), scope.weekStarts().iterator().next());   // Mon
        assertEquals(1, scope.quarterStarts().size());
        assertEquals(LocalDate.of(2026, 4, 1), scope.quarterStarts().iterator().next()); // Q2
        assertEquals(1, scope.yearStarts().size());
        assertEquals(LocalDate.of(2026, 1, 1), scope.yearStarts().iterator().next());
    }

    /** Five dates within the same ISO week → one week, one quarter, one year. */
    @Test
    void weekDeduplicates() {
        PipelineWorkflowImpl.CascadeScope scope =
                PipelineWorkflowImpl.computeCascadeScope(List.of(
                        LocalDate.of(2026, 5, 11),
                        LocalDate.of(2026, 5, 12),
                        LocalDate.of(2026, 5, 13),
                        LocalDate.of(2026, 5, 14),
                        LocalDate.of(2026, 5, 15)));
        assertEquals(1, scope.weekStarts().size());
    }

    /** Dates spanning two weeks → two weeks. */
    @Test
    void twoWeeksSpan() {
        PipelineWorkflowImpl.CascadeScope scope =
                PipelineWorkflowImpl.computeCascadeScope(List.of(
                        LocalDate.of(2026, 5, 15),    // Fri, week-of-05-11
                        LocalDate.of(2026, 5, 18)));  // Mon, week-of-05-18
        assertEquals(2, scope.weekStarts().size());
        assertTrue(scope.weekStarts().contains(LocalDate.of(2026, 5, 11)));
        assertTrue(scope.weekStarts().contains(LocalDate.of(2026, 5, 18)));
    }

    /** Quarter boundary: Mar 31 + Apr 1 → two quarters. */
    @Test
    void quarterBoundary() {
        PipelineWorkflowImpl.CascadeScope scope =
                PipelineWorkflowImpl.computeCascadeScope(List.of(
                        LocalDate.of(2026, 3, 31),
                        LocalDate.of(2026, 4, 1)));
        assertEquals(2, scope.quarterStarts().size());
        assertTrue(scope.quarterStarts().contains(LocalDate.of(2026, 1, 1)));   // Q1
        assertTrue(scope.quarterStarts().contains(LocalDate.of(2026, 4, 1)));   // Q2
    }

    /** Year boundary: Dec 31 + Jan 1 → two years. */
    @Test
    void yearBoundary() {
        PipelineWorkflowImpl.CascadeScope scope =
                PipelineWorkflowImpl.computeCascadeScope(List.of(
                        LocalDate.of(2026, 12, 31),
                        LocalDate.of(2027, 1, 1)));
        assertEquals(2, scope.yearStarts().size());
        assertTrue(scope.yearStarts().contains(LocalDate.of(2026, 1, 1)));
        assertTrue(scope.yearStarts().contains(LocalDate.of(2027, 1, 1)));
    }

    /** 12-day rerun (the tonight scenario): 2 weeks, 1 quarter, 1 year. */
    @Test
    void twelveDayOvernightScope() {
        PipelineWorkflowImpl.CascadeScope scope =
                PipelineWorkflowImpl.computeCascadeScope(List.of(
                        LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 12),
                        LocalDate.of(2026, 5, 13), LocalDate.of(2026, 5, 14),
                        LocalDate.of(2026, 5, 15),
                        LocalDate.of(2026, 5, 18), LocalDate.of(2026, 5, 19),
                        LocalDate.of(2026, 5, 20), LocalDate.of(2026, 5, 21),
                        LocalDate.of(2026, 5, 22),
                        LocalDate.of(2026, 5, 26), LocalDate.of(2026, 5, 27)));
        assertEquals(3, scope.weekStarts().size(),
                "12 days span 3 weeks (week-of-05-11, week-of-05-18, week-of-05-25): " + scope.weekStarts());
        assertEquals(1, scope.quarterStarts().size(), "all in Q2 2026");
        assertEquals(1, scope.yearStarts().size(), "all in 2026");
    }

    // ─── Stage 1 — expandDates (date list + range union + weekday filter) ──

    @Test
    void expandDates_explicitOnly() {
        java.util.List<LocalDate> out = PipelineWorkflowImpl.expandDates(
                List.of(LocalDate.of(2026, 5, 12), LocalDate.of(2026, 5, 13)),
                null);
        assertEquals(2, out.size());
        assertEquals(LocalDate.of(2026, 5, 12), out.get(0));
        assertEquals(LocalDate.of(2026, 5, 13), out.get(1));
    }

    @Test
    void expandDates_rangeExpansionExcludesWeekends() {
        // 2026-05-11 (Mon) through 2026-05-17 (Sun) — 5 weekdays
        java.util.List<LocalDate> out = PipelineWorkflowImpl.expandDates(
                null,
                List.of(new PipelineWorkflow.DateRange(
                        LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 17))));
        assertEquals(5, out.size());
        assertEquals(LocalDate.of(2026, 5, 11), out.get(0));   // Mon
        assertEquals(LocalDate.of(2026, 5, 15), out.get(4));   // Fri
    }

    @Test
    void expandDates_unionAndDedup() {
        // dates [12, 13] + range [12..15] should produce 4 unique weekdays
        java.util.List<LocalDate> out = PipelineWorkflowImpl.expandDates(
                List.of(LocalDate.of(2026, 5, 12), LocalDate.of(2026, 5, 13)),
                List.of(new PipelineWorkflow.DateRange(
                        LocalDate.of(2026, 5, 12), LocalDate.of(2026, 5, 15))));
        assertEquals(4, out.size());
    }

    @Test
    void expandDates_skipsWeekendsInExplicitList() {
        // 2026-05-16 is Saturday; should be filtered
        java.util.List<LocalDate> out = PipelineWorkflowImpl.expandDates(
                List.of(LocalDate.of(2026, 5, 15), LocalDate.of(2026, 5, 16)),
                null);
        assertEquals(1, out.size());
        assertEquals(LocalDate.of(2026, 5, 15), out.get(0));
    }

    @Test
    void expandDates_handlesReversedRange() {
        // Swap from/to — should still expand correctly
        java.util.List<LocalDate> out = PipelineWorkflowImpl.expandDates(
                null,
                List.of(new PipelineWorkflow.DateRange(
                        LocalDate.of(2026, 5, 15), LocalDate.of(2026, 5, 11))));
        assertEquals(5, out.size());
    }

    @Test
    void expandDates_multipleRangesUnion() {
        // Two non-overlapping ranges + an individual date should all union.
        java.util.List<LocalDate> out = PipelineWorkflowImpl.expandDates(
                List.of(LocalDate.of(2026, 5, 27)),  // Wed
                List.of(
                        new PipelineWorkflow.DateRange(
                                LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 15)),
                        new PipelineWorkflow.DateRange(
                                LocalDate.of(2026, 5, 18), LocalDate.of(2026, 5, 22))));
        assertEquals(11, out.size(), "5 weekdays + 5 weekdays + 1 individual = 11");
        assertEquals(LocalDate.of(2026, 5, 11), out.get(0));
        assertEquals(LocalDate.of(2026, 5, 27), out.get(10));
    }

    @Test
    void expandDates_overlappingRangesDedup() {
        // Overlapping ranges should produce the union, not duplicates.
        java.util.List<LocalDate> out = PipelineWorkflowImpl.expandDates(
                null,
                List.of(
                        new PipelineWorkflow.DateRange(
                                LocalDate.of(2026, 5, 11), LocalDate.of(2026, 5, 15)),
                        new PipelineWorkflow.DateRange(
                                LocalDate.of(2026, 5, 13), LocalDate.of(2026, 5, 18))));
        // 11..15 (5 weekdays) ∪ 13..18 (4 weekdays) = 11..18 (6 weekdays)
        assertEquals(6, out.size());
    }

    @Test
    void expandDates_emptyWhenAllNull() {
        java.util.List<LocalDate> out = PipelineWorkflowImpl.expandDates(null, null);
        assertTrue(out.isEmpty());
    }

    /** Chronological ordering preserved (TreeSet → LinkedHashSet). */
    @Test
    void chronologicalOrder() {
        PipelineWorkflowImpl.CascadeScope scope =
                PipelineWorkflowImpl.computeCascadeScope(List.of(
                        LocalDate.of(2026, 5, 22),    // input out of order
                        LocalDate.of(2026, 5, 11),
                        LocalDate.of(2026, 5, 18)));
        List<LocalDate> weeks = new java.util.ArrayList<>(scope.weekStarts());
        assertEquals(List.of(
                LocalDate.of(2026, 5, 11),
                LocalDate.of(2026, 5, 18)), weeks,
                "weekStarts should be chronological even when input is unordered");
    }
}
