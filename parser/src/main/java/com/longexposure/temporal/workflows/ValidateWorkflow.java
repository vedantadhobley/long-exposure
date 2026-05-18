package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.RecordValidationActivity;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.time.LocalDate;

/**
 * Validate workflow — single source of truth for the three-way
 * triangle validation (DPLS↔DEEP, DPLS→TOPS, DEEP→TOPS). Runs the
 * three legs concurrently against pcap.gz files already on disk
 * and upserts the per-leg results to {@code validation_runs}.
 *
 * <p>Called as a child workflow by {@link DailyPipelineWorkflow}
 * during the cron-driven nightly run. Also exposed as a top-level
 * workflow for ad-hoc developer invocation:
 * <pre>
 *   docker exec long-exposure-dev-temporal temporal workflow start \
 *     --task-queue long-exposure-daily-pipeline \
 *     --type ValidateWorkflow --workflow-id validate-YYYYMMDD \
 *     --input '"YYYY-MM-DD"'
 * </pre>
 *
 * <p>Returns the full {@link RecordValidationActivity.Result} so
 * callers (including {@code DailyPipelineWorkflow}) get both the
 * overall status string AND the per-leg notes JSON.
 */
@WorkflowInterface
public interface ValidateWorkflow {

    @WorkflowMethod
    RecordValidationActivity.Result run(LocalDate targetDate);
}
