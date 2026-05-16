package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.SelectTopEventsActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.LocalDate;

/**
 * Standalone workflow that runs only {@link SelectTopEventsActivity} for
 * a date whose {@code scored_events} are already populated in Postgres.
 * Useful for iterating on the per-scorer caps (or any selection logic)
 * without re-running the multi-minute scoring step.
 */
public final class SelectOnlyWorkflow {

    @WorkflowInterface
    public interface Iface {
        String TASK_QUEUE = "long-exposure-daily-pipeline";

        @WorkflowMethod(name = "SelectOnlyWorkflow")
        long run(LocalDate targetDate);
    }

    public static final class Impl implements Iface {

        private static final Logger LOG = Workflow.getLogger(Impl.class);

        private final SelectTopEventsActivity selectTopEvents = Workflow.newActivityStub(
                SelectTopEventsActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofMinutes(5))
                        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                        .build());

        @Override
        public long run(final LocalDate date) {
            LOG.info("select-only start  date={}", date);
            long n = selectTopEvents.selectTopEvents(date);
            LOG.info("select-only done  date={} selected_events={}", date, n);
            return n;
        }
    }
}
