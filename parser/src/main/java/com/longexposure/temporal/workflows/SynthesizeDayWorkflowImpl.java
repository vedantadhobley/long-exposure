package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.SynthesizeDayActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.LocalDate;

public final class SynthesizeDayWorkflowImpl implements SynthesizeDayWorkflow {

    private static final Logger LOG = Workflow.getLogger(SynthesizeDayWorkflowImpl.class);

    private final SynthesizeDayActivity synthesize = Workflow.newActivityStub(
            SynthesizeDayActivity.class,
            ActivityOptions.newBuilder()
                    // Dispatch on the narration task queue (shares JVM-wide
                    // LLM concurrency with DESCRIBE and INTERPRET).
                    .setTaskQueue(DailyPipelineWorkflow.NARRATION_TASK_QUEUE)
                    // start-to-close = MAX_LLM_ATTEMPTS=3 × ~90s LLM call ≈ 4.5 min
                    // plus a buffer for the surrounding load/aggregate/upsert work.
                    .setStartToCloseTimeout(Duration.ofMinutes(10))
                    // 1-min heartbeat is now a REAL liveness signal — the activity
                    // uses BackgroundHeartbeat (commit landing 2026-05-28 evening)
                    // to call actx.heartbeat() every 30s while the blocking LLM
                    // HTTP call runs. If the heartbeat times out at 1 min the
                    // activity is genuinely stuck, not just busy. Replaces the
                    // band-aid 5-min heartbeat from commit 15af21f.
                    .setHeartbeatTimeout(Duration.ofMinutes(1))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(15))
                            .setMaximumAttempts(2)
                            .build())
                    .build());

    @Override
    public long run(final LocalDate date) {
        LOG.info("synthesize start  date={}", date);
        long result = synthesize.synthesize(date);
        LOG.info("synthesize done  date={} written={}", date, result);
        return result;
    }
}
