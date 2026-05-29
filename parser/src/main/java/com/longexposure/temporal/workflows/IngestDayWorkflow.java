package com.longexposure.temporal.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.time.LocalDate;

/**
 * Luv-side per-day chain (no LLM): idempotency check + Download + Parse +
 * Validate + Score + Select. Returns a status string the caller uses to
 * decide whether to fire the LLM chain.
 *
 * <p>Stage 3 (2026-05-29) — extracted from {@link DailyPipelineWorkflowImpl}
 * so {@link PipelineWorkflow} can sliding-window IngestDay[N+1] in
 * parallel with {@link LlmDayWorkflow}[N] (Phase A/B overlap, Stage 5).
 *
 * <p>Returns one of:
 * <ul>
 *   <li>{@code "skipped_already_ingested"} — idempotency hit, no work done
 *   <li>{@code "skipped_no_data"} — weekend/holiday, NotATradingDay
 *   <li>{@code "ok"} — parse + validate succeeded, score complete
 *   <li>{@code "unverified"} — parse ok but validation below threshold
 *   <li>{@code "validation_failed_data_ok"} — validation activity errored, data ok
 *   <li>{@code "parse_failed"} — parse exhausted retries
 * </ul>
 *
 * <p>Caller fires LlmDay only when status is {@code "ok"} or
 * {@code "unverified"} (unverified data is still queryable).
 */
@WorkflowInterface
public interface IngestDayWorkflow {

    @WorkflowMethod
    String run(Input input);

    record Input(
            LocalDate date,
            boolean   pollUntilReady,
            boolean   forceReingest) {}
}
