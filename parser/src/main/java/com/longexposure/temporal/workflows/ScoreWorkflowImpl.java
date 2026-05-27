package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.EnrichAnalyticsActivity;
import com.longexposure.temporal.activities.EnrichWithCoOccurrenceActivity;
import com.longexposure.temporal.activities.MaterializeOrderLifecycleActivity;
import com.longexposure.temporal.activities.RefreshBaselinesActivity;
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

    private final RefreshBaselinesActivity refreshBaselines = Workflow.newActivityStub(
            RefreshBaselinesActivity.class,
            ActivityOptions.newBuilder()
                    // Single-day cagg refresh — fast (a day's bucket over a tiny
                    // cagg), but allow headroom for a first-run full materialize.
                    .setStartToCloseTimeout(Duration.ofMinutes(15))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                    .build());

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

    private final EnrichWithCoOccurrenceActivity enrich = Workflow.newActivityStub(
            EnrichWithCoOccurrenceActivity.class,
            ActivityOptions.newBuilder()
                    // Per-symbol nested-interval lookup for the top
                    // candidates. Even 1000 candidates × ~10ms each
                    // would finish well within the budget.
                    .setStartToCloseTimeout(Duration.ofMinutes(15))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                    .build());

    private final SelectTopEventsActivity selectTopEvents = Workflow.newActivityStub(
            SelectTopEventsActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                    .build());

    private final EnrichAnalyticsActivity enrichAnalytics = Workflow.newActivityStub(
            EnrichAnalyticsActivity.class,
            ActivityOptions.newBuilder()
                    // Per-event lazy analytics over the ~90–170 selected events
                    // (order_lifecycle lookups now; book replay later). Generous
                    // budget for the eventual replay tier; heartbeated.
                    .setStartToCloseTimeout(Duration.ofMinutes(30))
                    .setHeartbeatTimeout(Duration.ofMinutes(10))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                    .build());

    @Override
    public long run(final LocalDate date) {
        LOG.info("score start  date={}", date);
        // Ensure the volume cagg includes this day before the inter-day scorer
        // reads it (the hourly background policy may not have caught up yet).
        refreshBaselines.refreshBaselines(date);
        long materialized = materialize.materializeOrderLifecycle(date);
        LOG.info("order_lifecycle materialized  date={} rows={}", date, materialized);
        long scored = scoreEvents.scoreEvents(date, null);
        long enriched = enrich.enrichWithCoOccurrence(date);
        LOG.info("co-occurrence enrichment done  date={} parents_enriched={}", date, enriched);
        long selected = selectTopEvents.selectTopEvents(date);
        // Post-select per-event analytics enrichment (order_lifecycle / window /
        // book-state derived) — only the ~90–170 narratable events.
        long analyticsEnriched = enrichAnalytics.enrichAnalytics(date);
        LOG.info("score done  date={} lifecycle={} scored_events={} enriched={} selected_events={} analytics_enriched={}",
                date, materialized, scored, enriched, selected, analyticsEnriched);
        return scored;
    }
}
