package com.longexposure.temporal.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.time.LocalDate;

/**
 * Parses the DPLS feed for a trading date and writes ~360 M wire-format
 * rows across 13 hypertables via COPY. Idempotent — the activity
 * pre-cleans all DPLS rows for the date before parsing.
 *
 * <p>Returns the total message count parsed for telemetry. Throws
 * if parse fails (parent decides whether to continue with scoring or
 * fail the pipeline).
 *
 * <p>Called as a child workflow by {@link DailyPipelineWorkflow}. Also
 * usable standalone for replay:
 * <pre>
 *   docker exec long-exposure-dev-temporal temporal workflow start \
 *     --task-queue long-exposure-daily-pipeline \
 *     --type ParseWorkflow --workflow-id parse-YYYYMMDD \
 *     --input '{"targetDate":"YYYY-MM-DD","forceReingest":true}'
 * </pre>
 */
@WorkflowInterface
public interface ParseWorkflow {

    @WorkflowMethod
    long run(Input input);

    record Input(
            LocalDate targetDate,
            boolean   forceReingest
    ) {}
}
