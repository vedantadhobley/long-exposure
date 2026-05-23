package com.longexposure.temporal.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.time.LocalDate;

/**
 * Ad-hoc INTERPRET workflow. Runs {@link com.longexposure.temporal.activities.InterpretEventActivity}
 * for every event in {@code selected_events} on the given trading date,
 * producing interpretive narrative-context prose alongside the existing
 * per-event DESCRIBE narrations.
 *
 * <p>Concurrency model: identical to {@link NarrateWorkflow} — fans out
 * one activity per event with a 2-in-flight sliding window. Dispatches
 * on the {@link DailyPipelineWorkflow#NARRATION_TASK_QUEUE} task queue,
 * whose worker is capped at 2 concurrent activities. Combined with the
 * JVM-wide {@code LlamaClient} semaphore this guarantees no more than
 * 2 simultaneous LLM calls against {@code llama-large.joi}.
 *
 * <p><b>Operational rule</b>: never run more than one LLM-bearing
 * workflow at a time. NarrateWorkflow and InterpretWorkflow both
 * compete for the same 2 LLM slots; running them concurrently
 * serializes them at the activity level but wastes Temporal scheduling
 * cycles. Run them sequentially.
 *
 * <p>Trigger:
 * <pre>
 *   docker exec long-exposure-dev-temporal temporal workflow start \
 *     --task-queue long-exposure-daily-pipeline \
 *     --type InterpretWorkflow --workflow-id interpret-YYYYMMDD \
 *     --input '[YYYY,M,D]'
 * </pre>
 */
@WorkflowInterface
public interface InterpretWorkflow {

    @WorkflowMethod
    long run(LocalDate targetDate);
}
