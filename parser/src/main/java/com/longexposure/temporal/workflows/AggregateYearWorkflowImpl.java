package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.AggregateYearActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.LocalDate;

public final class AggregateYearWorkflowImpl implements AggregateYearWorkflow {

    private static final Logger LOG = Workflow.getLogger(AggregateYearWorkflowImpl.class);

    private final AggregateYearActivity aggregate = Workflow.newActivityStub(
            AggregateYearActivity.class,
            ActivityOptions.newBuilder()
                    .setTaskQueue(DailyPipelineWorkflow.NARRATION_TASK_QUEUE)
                    .setStartToCloseTimeout(Duration.ofMinutes(10))
                    .setHeartbeatTimeout(Duration.ofMinutes(1))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(15))
                            .setMaximumAttempts(2)
                            .build())
                    .build());

    @Override
    public long run(final LocalDate anyDateInYear) {
        LOG.info("aggregate-year start  anyDateInYear={}", anyDateInYear);
        long result = aggregate.aggregate(anyDateInYear);
        LOG.info("aggregate-year done  anyDateInYear={} written={}", anyDateInYear, result);
        return result;
    }
}
