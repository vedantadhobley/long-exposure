package com.longexposure.temporal.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Daily ingest pipeline — downloads all three IEX HIST feeds (DPLS,
 * DEEP, TOPS) for a trading date, parses DPLS into Postgres, validates
 * the triangle, scores → DESCRIBE → INTERPRET → SYNTHESIZE → AGGREGATE,
 * compresses the day's chunks, cleans up raw files, and (in cron mode)
 * runs the week-aligned 2-week retention sweep.
 *
 * <p>Same workflow class for both cron-scheduled and ad-hoc executions;
 * behavior differs only by the input flags. See
 * {@link DailyPipelineWorkflowInput} and {@code docs/temporal-design.md}
 * for the full design.
 *
 * <p>Returns a string summarizing the run's outcome — one of the status
 * values documented in {@code schema.sql}'s {@code pipeline_runs.status}
 * column comment.
 */
@WorkflowInterface
public interface DailyPipelineWorkflow {

    /** Task queue this workflow + most of its activities are registered on. */
    String TASK_QUEUE = "long-exposure-daily-pipeline";

    /**
     * Dedicated task queue for LLM-bound activities (currently just
     * {@code NarrateEventActivity}). Has its own worker pool with
     * {@code setMaxConcurrentActivityExecutionSize(2)} so the
     * single-GPU {@code llama-large.joi} sees at most 2 concurrent
     * inference requests. Separated from the main pipeline queue so
     * heavy workflow fan-outs ({@code NarrateWorkflow} fires 90
     * activities) don't tie up worker threads — the surplus sits in
     * Temporal's queue until a slot is free.
     */
    String NARRATION_TASK_QUEUE = "long-exposure-narration";

    /** Workflow ID prefix; full ID is {@code daily-pipeline-{trading_date}}. */
    String WORKFLOW_ID_PREFIX = "daily-pipeline-";

    @WorkflowMethod
    String run(DailyPipelineWorkflowInput input);
}
