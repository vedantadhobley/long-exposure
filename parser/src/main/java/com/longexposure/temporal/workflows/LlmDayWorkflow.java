package com.longexposure.temporal.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.time.LocalDate;

/**
 * Per-day LLM chain orchestrator: {@link NarrateWorkflow} → {@link InterpretWorkflow}
 * → {@link SynthesizeDayWorkflow}, strictly sequential per the
 * one-LLM-workflow-at-a-time rule on joi's GPU mutex.
 *
 * <p>Extracted in Stage 2 (2026-05-29) so the LLM chain can be invoked
 * uniformly from:
 * <ul>
 *   <li>{@link DailyPipelineWorkflow} (full per-day pipeline)
 *   <li>{@link PipelineWorkflow} {@code LLM_CHAIN} mode (skip parse/score)
 *   <li>{@link PipelineWorkflow} {@code SCORE_AND_LLM} mode (planned)
 *   <li>Ad-hoc replay for a single date via Temporal CLI
 * </ul>
 *
 * <p>Each step's content-addressing handles cache hit/miss internally — a
 * narrate workflow with unchanged inputs returns in a few seconds (cache
 * relink). A PROMPT_VERSION bump invalidates the cache and forces fresh
 * LLM work.
 *
 * <p>Return: number of synthesize-day rows written (0 or 1; 0 means the
 * day had no selected events).
 */
@WorkflowInterface
public interface LlmDayWorkflow {

    @WorkflowMethod
    long run(LocalDate date);
}
