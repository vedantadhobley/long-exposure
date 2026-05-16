package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.NarrateEventsActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.LocalDate;

/**
 * Standalone narration workflow. Runs {@link NarrateEventsActivity}
 * for a date whose {@code selected_events} are populated. Useful for
 * iterating on prompts / verifier rules without re-scoring.
 */
public final class NarrateOnlyWorkflow {

    @WorkflowInterface
    public interface Iface {
        String TASK_QUEUE = "long-exposure-daily-pipeline";

        @WorkflowMethod(name = "NarrateOnlyWorkflow")
        long run(LocalDate targetDate);
    }

    public static final class Impl implements Iface {

        private static final Logger LOG = Workflow.getLogger(Impl.class);

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
            LOG.info("narrate-only start  date={}", date);
            long n = narrate.narrateEvents(date);
            LOG.info("narrate-only done  date={} narrated={}", date, n);
            return n;
        }
    }
}
