package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.NarrateEventsActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.LocalDate;

public final class NarrateWorkflowImpl implements NarrateWorkflow {

    private static final Logger LOG = Workflow.getLogger(NarrateWorkflowImpl.class);

    private final NarrateEventsActivity narrate = Workflow.newActivityStub(
            NarrateEventsActivity.class,
            ActivityOptions.newBuilder()
                    // 90 events × ~12 sec each / 2 parallel slots ≈ 9 min lower bound;
                    // wide budget for slow days and prompt iteration.
                    .setStartToCloseTimeout(Duration.ofMinutes(60))
                    .setHeartbeatTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                    .build());

    @Override
    public long run(final LocalDate date) {
        LOG.info("narrate start  date={}", date);
        long n = narrate.narrateEvents(date);
        LOG.info("narrate done  date={} narrated={}", date, n);
        return n;
    }
}
