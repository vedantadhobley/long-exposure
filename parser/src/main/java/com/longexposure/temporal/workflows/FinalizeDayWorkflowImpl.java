package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.CompressChunksActivity;
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

        LOG.info("finalize end  date={}", input.date());
    }
}
