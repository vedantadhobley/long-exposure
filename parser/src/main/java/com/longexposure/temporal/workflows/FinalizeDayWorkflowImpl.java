package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.CompressChunksActivity;
import com.longexposure.temporal.activities.PruneStaleNarrationsActivity;
import com.longexposure.temporal.activities.RetainRawFilesActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;

/**
 * Luv-side post-LLM chain: compress chunks + cleanup. Stage 3.
 */
public final class FinalizeDayWorkflowImpl implements FinalizeDayWorkflow {

    private static final Logger LOG = Workflow.getLogger(FinalizeDayWorkflowImpl.class);
    private static final int RETENTION_WEEKS = 2;
    /** Wall-clock TTL for /storage/raw/*.pcap.gz, independent of pipeline status. */
    private static final int RAW_FILE_RETAIN_DAYS = 3;

    private final CompressChunksActivity compressor = Workflow.newActivityStub(
            CompressChunksActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(60))
                    .setHeartbeatTimeout(Duration.ofMinutes(10))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(30))
                            .setMaximumAttempts(2)
                            .build())
                    .build());

    private final PruneStaleNarrationsActivity pruner = Workflow.newActivityStub(
            PruneStaleNarrationsActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(10))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(15))
                            .setMaximumAttempts(2)
                            .build())
                    .build());

    private final RetainRawFilesActivity rawRetain = Workflow.newActivityStub(
            RetainRawFilesActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(15))
                            .setMaximumAttempts(2)
                            .build())
                    .build());

    private final CleanupWorkflow cleanupChild = Workflow.newChildWorkflowStub(
            CleanupWorkflow.class,
            ChildWorkflowOptions.newBuilder()
                    .setWorkflowId(Workflow.getInfo().getWorkflowId() + "-cleanup")
                    .setTaskQueue(DailyPipelineWorkflow.TASK_QUEUE)
                    .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_TERMINATE)
                    .build());

    @Override
    public void run(final Input input) {
        LOG.info("finalize start  date={} deleteFiles={} retention={}",
                input.date(), input.deleteFiles(), input.runRetentionSweep());

        try {
            long compressed = compressor.compress(input.date());
            LOG.info("compressed chunks  date={} count={}", input.date(), compressed);
        } catch (ActivityFailure af) {
            LOG.warn("compress chunks failed (policy will retry later)  date={} err={}",
                    input.date(), af.getMessage());
        }

        try {
            cleanupChild.run(new CleanupWorkflow.Input(
                    input.date(), input.deleteFiles(),
                    input.runRetentionSweep(), RETENTION_WEEKS));
        } catch (ChildWorkflowFailure cwf) {
            LOG.warn("cleanup child failed  date={} err={}",
                    input.date(), cwf.getMessage());
        }

        // Collapse re-run accumulation in narratives / interpretations. This is
        // a no-op on steady-state cron days (each event narrated exactly once);
        // it only does work when a re-narrate / re-score has produced multiple
        // hashes per content-key.
        try {
            PruneStaleNarrationsActivity.PruneResult pruned = pruner.pruneDate(input.date());
            if (pruned.narrativesDeleted() > 0 || pruned.interpretationsDeleted() > 0) {
                LOG.info("pruned re-run orphans  date={} narratives_del={} interp_del={}",
                        input.date(), pruned.narrativesDeleted(), pruned.interpretationsDeleted());
            }
        } catch (ActivityFailure af) {
            LOG.warn("prune failed (non-fatal)  date={} err={}", input.date(), af.getMessage());
        }

        // Raw .pcap.gz wall-clock TTL (independent of pipeline status). Frees
        // disk regardless of whether this run came from cron, ad-hoc, or a
        // failed parse. ~10 min to re-download from IEX HIST if ever needed.
        try {
            RetainRawFilesActivity.RetainResult retain = rawRetain.retain(RAW_FILE_RETAIN_DAYS);
            if (retain.filesDeleted() > 0) {
                LOG.info("retained raw files  date={} deleted={} bytes_freed={}",
                        input.date(), retain.filesDeleted(), retain.bytesFreed());
            }
        } catch (ActivityFailure af) {
            LOG.warn("raw retain failed (non-fatal)  date={} err={}", input.date(), af.getMessage());
        }

        LOG.info("finalize end  date={}", input.date());
    }
}
