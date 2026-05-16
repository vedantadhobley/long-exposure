package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.ScoreEventsActivity;
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
 * Standalone scoring workflow. Runs {@link ScoreEventsActivity} for a
 * date whose DPLS data is already loaded in Postgres. Useful for:
 *
 * <ul>
 *   <li>Iterating on a new {@code EventScorer} without re-parsing
 *   <li>Re-scoring a date after adding scorers to {@code EventScorerRegistry}
 *   <li>Backfilling scored events across historical dates
 * </ul>
 */
public final class ScoreOnlyWorkflow {

    @WorkflowInterface
    public interface Iface {
        String TASK_QUEUE = "long-exposure-daily-pipeline";

        @WorkflowMethod(name = "ScoreOnlyWorkflow")
        long run(LocalDate targetDate);
    }

    public static final class Impl implements Iface {

        private static final Logger LOG = Workflow.getLogger(Impl.class);

        private final ScoreEventsActivity scoreEvents = Workflow.newActivityStub(
                ScoreEventsActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofMinutes(90))
                        .setHeartbeatTimeout(Duration.ofMinutes(15))
                        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                        .build());

        private final SelectTopEventsActivity selectTopEvents = Workflow.newActivityStub(
                SelectTopEventsActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofMinutes(5))
                        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                        .build());

        @Override
        public long run(final LocalDate date) {
            LOG.info("score-only start  date={}", date);
            long scored = scoreEvents.scoreEvents(date, null);
            long selected = selectTopEvents.selectTopEvents(date);
            LOG.info("score-only done  date={} scored_events={} selected_events={}",
                    date, scored, selected);
            return scored;
        }
    }
}
