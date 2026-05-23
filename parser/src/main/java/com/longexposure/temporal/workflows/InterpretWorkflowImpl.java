package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.InterpretEventActivity;
import com.longexposure.temporal.activities.ListSelectedEventsActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * INTERPRET workflow. Same shape as {@link NarrateWorkflowImpl}: reads
 * the day's {@code selected_events} via a tiny lister activity, fans
 * out one {@link InterpretEventActivity} per event with a 2-in-flight
 * sliding-window dispatch.
 *
 * <p>Three-layer concurrency control identical to NarrateWorkflow's
 * (workflow-side window + worker-side cap + JVM-side semaphore in
 * {@code LlamaClient}). The two workflows share the same task queue,
 * so even if both were running their activities would compete for the
 * same 2 LLM slots — see the "never two LLM workflows concurrently"
 * operational rule.
 */
public final class InterpretWorkflowImpl implements InterpretWorkflow {

    private static final Logger LOG = Workflow.getLogger(InterpretWorkflowImpl.class);

    /** Max activities the workflow has in flight at any one moment. */
    private static final int MAX_IN_FLIGHT = 2;

    private final InterpretEventActivity interpret = Workflow.newActivityStub(
            InterpretEventActivity.class,
            ActivityOptions.newBuilder()
                    // Dispatch on the narration-dedicated task queue. The
                    // worker is configured with max concurrent execution = 2,
                    // capping LLM concurrency at the GPU's safe parallelism.
                    .setTaskQueue(DailyPipelineWorkflow.NARRATION_TASK_QUEUE)
                    // Each event = 2 small SQL queries + 1 LLM call (~10-15
                    // sec at 2-concurrent on joi). 5 min start-to-close is
                    // comfortable headroom for tail latency.
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setHeartbeatTimeout(Duration.ofMinutes(1))
                    .setScheduleToCloseTimeout(Duration.ofHours(1))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(15))
                            .setMaximumAttempts(2)
                            .build())
                    .build());

    /** Lister to enumerate the day's selected_ids. Shared with NarrateWorkflow. */
    private final ListSelectedEventsActivity lister = Workflow.newActivityStub(
            ListSelectedEventsActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                    .build());

    @Override
    public long run(final LocalDate date) {
        LOG.info("interpret start  date={}", date);

        List<Long> selectedIds = lister.list(date);
        LOG.info("fan out (windowed, max_in_flight={})  date={} events={}",
                MAX_IN_FLIGHT, date, selectedIds.size());

        // Sliding-window dispatch — identical to NarrateWorkflowImpl.
        List<Promise<Long>> all = new ArrayList<>(selectedIds.size());
        List<Promise<Long>> inFlight = new ArrayList<>(MAX_IN_FLIGHT);

        for (Long selectedId : selectedIds) {
            while (inFlight.size() >= MAX_IN_FLIGHT) {
                try {
                    Promise.anyOf(inFlight.toArray(new Promise[0])).get();
                } catch (Exception ignored) {
                    // Per-activity errors are captured in the drain loop below.
                }
                inFlight.removeIf(Promise::isCompleted);
            }
            Promise<Long> p = Async.function(interpret::interpret, date, selectedId);
            all.add(p);
            inFlight.add(p);
        }

        // Drain + collect outcomes.
        long interpreted = 0;
        long failed = 0;
        for (int i = 0; i < all.size(); i++) {
            try {
                interpreted += all.get(i).get();
            } catch (ActivityFailure af) {
                failed++;
                LOG.warn("event interpretation exhausted retries  selected_id={} err={}",
                        selectedIds.get(i), af.getMessage());
            }
        }

        LOG.info("interpret done  date={} interpreted={} failed={}", date, interpreted, failed);
        return interpreted;
    }
}
