package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.AggregateQuarterActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.LocalDate;

public final class AggregateQuarterWorkflowImpl implements AggregateQuarterWorkflow {

    private static final Logger LOG = Workflow.getLogger(AggregateQuarterWorkflowImpl.class);

    private final AggregateQuarterActivity aggregate = Workflow.newActivityStub(
            AggregateQuarterActivity.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(DailyPipelineWorkflow.NARRATION_TASK_QUEUE)
                    // Larger context than weekly (~13 weekly paragraphs) but still
                    // small absolute (~5-10K tokens of input, ~700-token completion).
                    // 10 min start-to-close covers tail latency comfortably.
                    .setStartToCloseTimeout(Duration.ofMinutes(10))
                    .setHeartbeatTimeout(Duration.ofMinutes(1))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(15))
                            .setMaximumAttempts(2)
                            .build())
                    .build());

    @Override
    public long run(final LocalDate anyDateInQuarter) {
        LOG.info("aggregate-quarter start  anyDateInQuarter={}", anyDateInQuarter);
        long result = aggregate.aggregate(anyDateInQuarter);
        LOG.info("aggregate-quarter done  anyDateInQuarter={} written={}", anyDateInQuarter, result);
        return result;
    }
}
