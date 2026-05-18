package com.longexposure.temporal.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.time.LocalDate;

/**
 * Resolves URLs + downloads the three IEX feed files (DPLS, DEEP, TOPS)
 * for a trading date. All six operations (3 resolves + 3 downloads) run
 * in parallel.
 *
 * <p>Called as a child workflow by {@link DailyPipelineWorkflow}. Also
 * usable standalone for replay/backfill via:
 * <pre>
 *   docker exec long-exposure-dev-temporal temporal workflow start \
 *     --task-queue long-exposure-daily-pipeline \
 *     --type DownloadWorkflow --workflow-id download-YYYYMMDD \
 *     --input '{"targetDate":"YYYY-MM-DD","pollUntilReady":false}'
 * </pre>
 *
 * <p>Behavior:
 * <ul>
 *   <li>If any feed's URL resolves to "not a trading day" (weekend /
 *       holiday), the workflow throws an {@code ApplicationFailure}
 *       typed {@code NotATradingDay}. Parents catch this and
 *       short-circuit cleanly.
 *   <li>If a date's data isn't yet published by IEX, the workflow
 *       polls (15-min interval, 3-hour budget) when
 *       {@code pollUntilReady=true}, or fails fast when false. The
 *       cron path uses true; ad-hoc invocations use false.
 *   <li>Downloads are idempotent — a partial file is resumed; an
 *       already-complete file is skipped.
 * </ul>
 */
@WorkflowInterface
public interface DownloadWorkflow {

    @WorkflowMethod
    DownloadResult run(Input input);

    /**
     * Inputs to download. {@code pollUntilReady=true} for cron runs
     * (T+1 data may not be available exactly at midnight ET), false
     * for ad-hoc replay where we expect the date to already be
     * published.
     */
    record Input(
            LocalDate targetDate,
            boolean   pollUntilReady
    ) {}
}
