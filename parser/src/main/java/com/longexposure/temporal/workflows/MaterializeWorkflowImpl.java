package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.MaterializeOrderLifecycleActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.LocalDate;

public final class MaterializeWorkflowImpl implements MaterializeWorkflow {

    private static final Logger LOG = Workflow.getLogger(MaterializeWorkflowImpl.class);

    private final MaterializeOrderLifecycleActivity activity = Workflow.newActivityStub(
            MaterializeOrderLifecycleActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(60))
                    .setHeartbeatTimeout(Duration.ofMinutes(15))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                    .build());

    @Override
    public long run(final LocalDate date) {
        LOG.info("materialize start  date={}", date);
        long rows = activity.materializeOrderLifecycle(date);
        LOG.info("materialize done  date={} rows={}", date, rows);
        return rows;
    }
}
