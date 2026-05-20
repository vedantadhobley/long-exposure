package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.ListSelectedEventsActivity;
import com.longexposure.temporal.activities.NarrateEventActivity;
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
 * Narration workflow. Reads the day's {@code selected_events} and
 * processes them through a sliding-window of at most
 * {@link #MAX_IN_FLIGHT} activities at any given moment. Matches the
 * spin-cycle Python project's workflow-side {@code asyncio.Semaphore}
 * pattern.
 *
 * <p>Three layers of concurrency control, defense-in-depth:
 * <ol>
 *   <li><b>Workflow-side sliding window</b> (this class): the workflow
 *       blocks on {@code Promise.anyOf(inFlight).get()} before dispatching
 *       the next activity, so Temporal only sees ~{@link #MAX_IN_FLIGHT}
 *       activities scheduled at any moment. Keeps the Temporal UI clean.
 *   <li><b>Worker-side cap</b> ({@code setMaxConcurrentActivityExecutionSize(2)}
 *       on the {@link DailyPipelineWorkflow#NARRATION_TASK_QUEUE} worker):
 *       even if the workflow's window grew, the worker pulls at most 2
 *       activities into local execution.
 *   <li><b>JVM-side semaphore</b> ({@code Semaphore(2, fair)} inside
 *       {@link com.longexposure.llm.LlamaClient}): hard physical guard
 *       around the GPU. Engages regardless of how the activities got
 *       there.
 * </ol>
 *
 * <p>The selected-event id list comes from a tiny
 * {@link ListSelectedEventsActivity} — workflow code can't touch JDBC,
 * so even sub-second SQL goes through an activity.
 */
public final class NarrateWorkflowImpl implements NarrateWorkflow {

    private static final Logger LOG = Workflow.getLogger(NarrateWorkflowImpl.class);

    /**
     * Max activities the workflow has in flight at any one moment.
     * Matches the worker-side cap, so the workflow doesn't overshoot.
     */
    private static final int MAX_IN_FLIGHT = 2;

    private final NarrateEventActivity narrate = Workflow.newActivityStub(
            NarrateEventActivity.class,
            ActivityOptions.newBuilder()
                    // Dispatch on the narration-dedicated task queue, whose
                    // worker is configured with max concurrent execution = 2
                    // (matching the GPU's safe parallelism on llama-large.joi).
                    // The other 88 of 90 fan-out activities sit in Temporal's
                    // queue waiting for slots — they don't tie up local worker
                    // threads and their start-to-close timer doesn't begin
                    // until they're actually dispatched.
                    .setTaskQueue(DailyPipelineWorkflow.NARRATION_TASK_QUEUE)
                    // Each event = 2 LLM calls × ~10 sec + DB upsert.
                    // 5 min is comfortable headroom for tail latency.
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setHeartbeatTimeout(Duration.ofMinutes(1))
                    // scheduleToClose covers queue + execution. With 90
                    // events and 2-at-a-time = ~12 min, but allow a wide
                    // budget for slow days.
                    .setScheduleToCloseTimeout(Duration.ofHours(1))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(15))
                            .setMaximumAttempts(2)
                            .build())
                    .build());

    /**
     * Activity that returns the list of selected_ids for the date. A
     * 1-second SELECT — separated into its own activity because
     * workflow code can't do JDBC directly.
     */
    private final ListSelectedEventsActivity lister = Workflow.newActivityStub(
            ListSelectedEventsActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                    .build());

    @Override
    public long run(final LocalDate date) {
        LOG.info("narrate start  date={}", date);

        List<Long> selectedIds = lister.list(date);
        LOG.info("fan out (windowed, max_in_flight={})  date={} events={}",
                MAX_IN_FLIGHT, date, selectedIds.size());

        // Sliding-window dispatch: at any moment, at most MAX_IN_FLIGHT
        // activities are scheduled/running. The workflow holds back the
        // rest until a slot frees up. This keeps the Temporal UI showing
        // ~2 activities at a time rather than all 164 scheduled at once.
        List<Promise<Long>> all = new ArrayList<>(selectedIds.size());
        List<Promise<Long>> inFlight = new ArrayList<>(MAX_IN_FLIGHT);

        for (Long selectedId : selectedIds) {
            while (inFlight.size() >= MAX_IN_FLIGHT) {
                // Block until any in-flight activity completes. Wrap .get()
                // in try/catch — Promise.anyOf().get() rethrows whatever
                // exception the first-completing promise carries, which
                // would kill the whole workflow on a single LLM hiccup.
                // We just need to detect "something completed" to move the
                // dispatch loop forward; per-event outcome is captured in
                // the drain loop below where we have proper try/catch.
                try {
                    Promise.anyOf(inFlight.toArray(new Promise[0])).get();
                } catch (Exception ignored) {
                    // An in-flight activity failed; that's fine here —
                    // its error gets logged in the drain loop.
                }
                inFlight.removeIf(Promise::isCompleted);
            }
            Promise<Long> p = Async.function(narrate::narrate, date, selectedId);
            all.add(p);
            inFlight.add(p);
        }

        // Drain remaining in-flight activities + collect outcomes
        long narrated = 0;
        long failed = 0;
        for (int i = 0; i < all.size(); i++) {
            try {
                narrated += all.get(i).get();
            } catch (ActivityFailure af) {
                failed++;
                LOG.warn("event narration exhausted retries  selected_id={} err={}",
                        selectedIds.get(i), af.getMessage());
            }
        }

        LOG.info("narrate done  date={} narrated={} failed={}", date, narrated, failed);
        return narrated;
    }
}
