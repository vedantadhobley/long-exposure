package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.SelectTopEventsActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.LocalDate;

public final class SelectWorkflowImpl implements SelectWorkflow {

    private static final Logger LOG = Workflow.getLogger(SelectWorkflowImpl.class);

    private final SelectTopEventsActivity selectTopEvents = Workflow.newActivityStub(
            SelectTopEventsActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                    .build());

    @Override
    public long run(final LocalDate date) {
        LOG.info("select start  date={}", date);
        long n = selectTopEvents.selectTopEvents(date);
        LOG.info("select done  date={} selected_events={}", date, n);
        return n;
    }
}
