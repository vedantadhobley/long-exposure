package com.longexposure.temporal.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.time.LocalDate;

/**
 * Ad-hoc scoring workflow. Runs materialize → score → select for a
 * date whose DPLS data is already loaded in Postgres. Used for:
 *
 * <ul>
 *   <li>Iterating on a new {@code EventScorer} without re-parsing
 *   <li>Re-scoring a date after adding scorers to {@code EventScorerRegistry}
 *   <li>Backfilling scored + selected events across historical dates
 * </ul>
 *
 * <p>Includes the materialize step (it's idempotent — pre-cleans by
 * trading_date — so re-running is safe). Trigger:
 * <pre>
 *   docker exec long-exposure-dev-temporal temporal workflow start \
 *     --task-queue long-exposure-daily-pipeline \
 *     --type ScoreWorkflow --workflow-id score-YYYYMMDD \
 *     --input '"YYYY-MM-DD"'
 * </pre>
 */
@WorkflowInterface
public interface ScoreWorkflow {

    @WorkflowMethod
    long run(LocalDate targetDate);
}
