package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * Small DB-only activity that records workflow lifecycle to
 * {@code pipeline_runs}. The workflow uses it to:
 * <ul>
 *   <li>{@link #isAlreadyIngested(LocalDate)} — short-circuit when the
 *       date is already done and {@code force_reingest=false}.
 *   <li>{@link #startRun(LocalDate)} — write a {@code running}-status row
 *       and return its {@code run_id} for later completion.
 *   <li>{@link #completeRun} — set the row's status + completed_at +
 *       optional message count / validator status / notes JSON.
 * </ul>
 *
 * <p>All methods are quick (no parsing, no IO), so the default
 * retry policy is fine.
 */
@ActivityInterface
public interface PipelineRunRecorderActivity {

    @ActivityMethod
    boolean isAlreadyIngested(LocalDate tradingDate);

    @ActivityMethod
    String startRun(LocalDate tradingDate);

    @ActivityMethod
    void completeRun(
            String runId,
            String status,
            Long parserMessageCount,
            String validatorStatus,
            String notesJson);
}
