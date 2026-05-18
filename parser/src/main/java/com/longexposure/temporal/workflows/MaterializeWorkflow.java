package com.longexposure.temporal.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.time.LocalDate;

/**
 * Ad-hoc materialize workflow. Runs {@code MaterializeOrderLifecycleActivity}
 * for a single date whose DPLS data is already in Postgres. Used for:
 *
 * <ul>
 *   <li>Backfilling {@code order_lifecycle} for dates parsed before the
 *       activity existed.
 *   <li>Re-materializing after a schema change to {@code order_lifecycle}.
 *   <li>Ad-hoc iteration during development of lifecycle-dependent scorers.
 * </ul>
 *
 * <p>Trigger:
 * <pre>
 *   docker exec long-exposure-dev-temporal temporal workflow start \
 *     --task-queue long-exposure-daily-pipeline \
 *     --type MaterializeWorkflow --workflow-id materialize-YYYYMMDD \
 *     --input '"YYYY-MM-DD"'
 * </pre>
 */
@WorkflowInterface
public interface MaterializeWorkflow {

    @WorkflowMethod
    long run(LocalDate targetDate);
}
