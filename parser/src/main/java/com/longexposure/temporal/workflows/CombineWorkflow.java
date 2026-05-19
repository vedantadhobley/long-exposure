package com.longexposure.temporal.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.time.LocalDate;

/**
 * Ad-hoc combine + select workflow. Runs
 * {@code CombineRelatedEventsActivity} then {@code SelectTopEventsActivity}
 * for a date whose {@code scored_events} are already populated.
 *
 * <p>Used for iterating on cross-event linking and threshold-selection
 * logic without re-running the expensive materialize + score steps.
 *
 * <p>{@code DailyPipelineWorkflow} invokes the combine activity via
 * {@code ScoreWorkflow}, not this workflow — this is a developer
 * entry point.
 *
 * <p>Trigger:
 * <pre>
 *   docker exec long-exposure-dev-temporal temporal workflow start \
 *     --task-queue long-exposure-daily-pipeline \
 *     --type CombineWorkflow --workflow-id combine-YYYYMMDD \
 *     --input '"YYYY-MM-DD"'
 * </pre>
 */
@WorkflowInterface
public interface CombineWorkflow {

    @WorkflowMethod
    long run(LocalDate targetDate);
}
