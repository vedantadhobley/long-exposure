package com.longexposure.temporal.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.time.LocalDate;

/**
 * Ad-hoc selection workflow. Runs {@code SelectTopEventsActivity}
 * against existing {@code scored_events}. Used for iterating on
 * per-scorer caps or threshold logic without re-running the
 * multi-minute scoring step.
 *
 * <p>Trigger:
 * <pre>
 *   docker exec long-exposure-dev-temporal temporal workflow start \
 *     --task-queue long-exposure-daily-pipeline \
 *     --type SelectWorkflow --workflow-id select-YYYYMMDD \
 *     --input '"YYYY-MM-DD"'
 * </pre>
 */
@WorkflowInterface
public interface SelectWorkflow {

    @WorkflowMethod
    long run(LocalDate targetDate);
}
