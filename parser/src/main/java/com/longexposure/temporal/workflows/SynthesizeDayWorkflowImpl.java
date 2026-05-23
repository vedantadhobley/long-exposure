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
                    // ~22K-token prompt + ~500-token completion ≈ 25-40 sec
                    // for the LLM call. 5 min start-to-close is comfortable.
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
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
