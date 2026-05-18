package com.longexposure.temporal.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.time.LocalDate;

/**
 * Post-pipeline housekeeping: optionally delete the day's three
 * .pcap.gz files (when the day succeeded — failed days keep them for
 * forensics) and drop hypertable chunks older than the retention
 * window.
 *
 * <p>Called as a child workflow by {@link DailyPipelineWorkflow} at
 * the end of a successful run. Also usable standalone to force a
 * retention sweep without re-running the daily pipeline.
 *
 * <p>The two operations are bundled because they're both
 * "end-of-pipeline housekeeping". Cleanup of the files only fires
 * when the run actually succeeded; retention sweep fires
 * unconditionally because dropping old chunks is independent of
 * whether today's run was healthy.
 */
@WorkflowInterface
public interface CleanupWorkflow {

    @WorkflowMethod
    void run(Input input);

    record Input(
            LocalDate targetDate,
            boolean   deleteFiles,        // true when run finished OK; false to keep files for debugging
            boolean   runRetentionSweep,  // true in cron mode; false ad-hoc
            int       retentionDays       // typically 30
    ) {}
}
