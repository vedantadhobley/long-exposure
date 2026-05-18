package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.RefreshSymbolMetadataActivity;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Thin wrapper workflow around {@link RefreshSymbolMetadataActivity}.
 * Exists because Temporal schedules can only invoke workflows, not
 * activities directly.
 *
 * <p>Runs on the same task queue as the daily pipeline workflow so the
 * already-running worker picks it up — no separate task queue or worker
 * needed.
 */
@WorkflowInterface
public interface RefreshSymbolsWorkflow {

    String TASK_QUEUE  = DailyPipelineWorkflow.TASK_QUEUE;
    String WORKFLOW_ID = "refresh-symbols-weekly";

    @WorkflowMethod
    long run();
}
