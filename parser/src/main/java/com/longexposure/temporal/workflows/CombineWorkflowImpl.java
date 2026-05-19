package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.CombineRelatedEventsActivity;
import com.longexposure.temporal.activities.SelectTopEventsActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.LocalDate;

public final class CombineWorkflowImpl implements CombineWorkflow {

    private static final Logger LOG = Workflow.getLogger(CombineWorkflowImpl.class);

    private final CombineRelatedEventsActivity combine = Workflow.newActivityStub(
            CombineRelatedEventsActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(15))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                    .build());

    private final SelectTopEventsActivity selectTopEvents = Workflow.newActivityStub(
            SelectTopEventsActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                    .build());

    @Override
    public long run(final LocalDate date) {
        LOG.info("combine+select start  date={}", date);
        long combined = combine.combineRelatedEvents(date);
        long selected = selectTopEvents.selectTopEvents(date);
        LOG.info("combine+select done  date={} combined={} selected={}", date, combined, selected);
        return selected;
    }
}
