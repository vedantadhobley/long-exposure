package com.longexposure.temporal.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.time.LocalDate;

/**
 * Ad-hoc narration workflow. Runs {@code NarrateEventsActivity} for a
 * date whose {@code selected_events} are populated. Used for iterating
 * on prompts / verifier rules / model selection without re-scoring.
 *
 * <p>Trigger:
 * <pre>
 *   docker exec long-exposure-dev-temporal temporal workflow start \
 *     --task-queue long-exposure-daily-pipeline \
 *     --type NarrateWorkflow --workflow-id narrate-YYYYMMDD \
 *     --input '"YYYY-MM-DD"'
 * </pre>
 */
@WorkflowInterface
public interface NarrateWorkflow {

    @WorkflowMethod
    long run(LocalDate targetDate);
}
