package com.longexposure.temporal.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.time.LocalDate;

/**
 * Luv-side post-LLM per-day chain (no LLM): compress today's TimescaleDB
 * chunks + cleanup (delete .pcap.gz files on success) + retention sweep.
 *
 * <p>Stage 3 (2026-05-29) — extracted from {@link DailyPipelineWorkflowImpl}
 * so the post-LLM bookkeeping can run in parallel with subsequent days'
 * LLM work in {@link PipelineWorkflow}'s sliding window (Stage 5).
 *
 * <p>Idempotent: compress + cleanup are no-ops when their conditions
 * aren't met. Retention sweep runs whenever {@code runRetentionSweep}
 * is true.
 */
@WorkflowInterface
public interface FinalizeDayWorkflow {

    @WorkflowMethod
    void run(Input input);

    /**
     * @param date                trading date this finalization covers
     * @param deleteFiles         delete the day's 3 .pcap.gz files (only when
     *                            cron mode + parse+validate succeeded)
     * @param runRetentionSweep   run the week-aligned 2-week retention sweep
     */
    record Input(
            LocalDate date,
            boolean   deleteFiles,
            boolean   runRetentionSweep) {}
}
