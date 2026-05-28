package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * AGGREGATE quarter — one LLM call per quarter reading the quarter's ≤13
 * weekly rollups + the prior ~4 quarterly rollups (year-over-quarter trend
 * context). Mirror of {@link AggregateWeekActivity} one tier up. Per-week
 * input is the weekly {@code aggregate_text}; the activity rolls up the
 * weekly {@code week_aggregates} JSONB into a quarter-level view and writes
 * a single paragraph to {@code quarterly_aggregate}.
 *
 * <p><b>Dormant until enough weekly history accumulates.</b> Gated by
 * {@code MIN_WEEKS_FOR_QUARTER} (8); when the quarter contains fewer than
 * 8 weekly rollups the activity short-circuits and returns 0 without an
 * LLM call. First non-dormant fire is the first DailyPipelineWorkflow after
 * 8+ weeks have been aggregated in the current calendar quarter.
 *
 * <p>Reuses {@link com.longexposure.narration.SynthesisVerifier} (same
 * grounding + intent-denylist + streak-bound contract as SYNTHESIZE /
 * AggregateWeek). Runs on
 * {@link com.longexposure.temporal.workflows.DailyPipelineWorkflow#NARRATION_TASK_QUEUE}.
 */
@ActivityInterface
public interface AggregateQuarterActivity {

    /**
     * Aggregate the themes of the calendar quarter containing
     * {@code anyDateInQuarter}. Returns 1 on successful upsert or content-hash
     * skip; 0 when not enough weekly history (dormant).
     */
    // Explicit name: defaults to the capitalized method name ("Aggregate"),
    // which would collide with AggregateWeekActivity + AggregateYearActivity
    // (same method name across all three interfaces).
    @ActivityMethod(name = "AggregateQuarter")
    long aggregate(LocalDate anyDateInQuarter);
}
