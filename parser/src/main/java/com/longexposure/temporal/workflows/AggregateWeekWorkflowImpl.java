package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.AggregateWeekActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.LocalDate;

public final class AggregateWeekWorkflowImpl implements AggregateWeekWorkflow {

    private static final Logger LOG = Workflow.getLogger(AggregateWeekWorkflowImpl.class);

    private final AggregateWeekActivity aggregate = Workflow.newActivityStub(
            AggregateWeekActivity.class,
            ActivityOptions.newBuilder()
                    // Dispatch on the narration task queue (shares the JVM-wide
                    // LLM concurrency cap with DESCRIBE / INTERPRET / SYNTHESIZE).
                    .setTaskQueue(DailyPipelineWorkflow.NARRATION_TASK_QUEUE)
                    // Tiny prompt (~1-2K tokens) + ~500-token completion — fast.
                    // 5 min start-to-close is generous headroom.
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setHeartbeatTimeout(Duration.ofMinutes(1))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(15))
                            .setMaximumAttempts(2)
                            .build())
                    .build());

    @Override
    public long run(final LocalDate anyDateInWeek) {
        LOG.info("aggregate start  anyDateInWeek={}", anyDateInWeek);
        long result = aggregate.aggregate(anyDateInWeek);
        LOG.info("aggregate done  anyDateInWeek={} written={}", anyDateInWeek, result);
        return result;
    }
}
