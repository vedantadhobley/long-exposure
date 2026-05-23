package com.longexposure.temporal.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.time.LocalDate;

/**
 * SYNTHESIZE workflow — one LLM call per day producing the day's themes
 * paragraph from all per-event narrations + interpretations.
 *
 * <p>No fan-out, no concurrency cap concerns at the workflow level —
 * this is a single activity call. The JVM-wide {@code LlamaClient}
 * semaphore still applies, so running SYNTHESIZE while NARRATE or
 * INTERPRET is running would compete for LLM slots. Per the
 * operational rule, only one LLM-bearing workflow runs at a time.
 *
 * <p>Trigger:
 * <pre>
 *   docker exec long-exposure-dev-temporal temporal workflow start \
 *     --task-queue long-exposure-daily-pipeline \
 *     --type SynthesizeDayWorkflow --workflow-id synthesize-YYYYMMDD \
 *     --input '[YYYY,M,D]'
 * </pre>
 */
@WorkflowInterface
public interface SynthesizeDayWorkflow {

    @WorkflowMethod
    long run(LocalDate targetDate);
}
