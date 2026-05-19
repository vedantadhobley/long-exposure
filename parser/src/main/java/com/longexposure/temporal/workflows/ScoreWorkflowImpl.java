package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.MaterializeOrderLifecycleActivity;
import com.longexposure.temporal.activities.ScoreEventsActivity;
import com.longexposure.temporal.activities.SelectTopEventsActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.LocalDate;

public final class ScoreWorkflowImpl implements ScoreWorkflow {

    private static final Logger LOG = Workflow.getLogger(ScoreWorkflowImpl.class);

    private final MaterializeOrderLifecycleActivity materialize = Workflow.newActivityStub(
            MaterializeOrderLifecycleActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(60))
                    .setHeartbeatTimeout(Duration.ofMinutes(15))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                    .build());

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

    // NOTE: Cross-event linking (combine) is disabled as of 2026-05-19.
    // The interval-overlap rule produced clusters dominated by nested
    // events (e.g., post_cancel/layering events occurring INSIDE long
    // liquidity_withdrawal intervals), yielding 28-36-constituent
    // combined events whose narrations were awkward and broke the
    // verifier (nested breakdowns the verifier couldn't resolve).
    // The CombineRelatedEventsActivity + CombineWorkflow remain in the
    // codebase for future iteration on a smarter combining rule
    // (e.g., time-scale-aware, or absorbed-vs-adjacent distinction).
    // See docs/decisions.md.

    @Override
    public long run(final LocalDate date) {
        LOG.info("score start  date={}", date);
        long materialized = materialize.materializeOrderLifecycle(date);
        LOG.info("order_lifecycle materialized  date={} rows={}", date, materialized);
        long scored = scoreEvents.scoreEvents(date, null);
        long selected = selectTopEvents.selectTopEvents(date);
        LOG.info("score done  date={} lifecycle={} scored_events={} selected_events={}",
                date, materialized, scored, selected);
        return scored;
    }
}
