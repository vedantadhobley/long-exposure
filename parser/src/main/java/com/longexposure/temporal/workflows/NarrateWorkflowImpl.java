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
 * Narration workflow. Reads the day's {@code selected_events}, fans
 * out one {@link NarrateEventActivity} invocation per event, and
 * waits for all of them.
 *
 * <p>Effective concurrency comes from the worker config —
 * {@code setMaxConcurrentActivityExecutionSize(2)} on the
 * {@code NarrateEventActivity} type — not from the workflow code.
 * The workflow can fire 90 activities; Temporal dispatches at most
 * 2 simultaneously to the worker. The {@link com.longexposure.llm.LlamaClient}
 * semaphore is the JVM-level physical guard.
 *
 * <p>The selected-event lookup is done via a non-Temporal local query
 * inside the workflow's initial query step (NOT a workflow activity).
 * Actually scratch that — workflow code can't do JDBC. We pull the
 * list of selected_ids via a separate small activity.
 */
public final class NarrateWorkflowImpl implements NarrateWorkflow {

    private static final Logger LOG = Workflow.getLogger(NarrateWorkflowImpl.class);

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
        LOG.info("fan out  date={} events={}", date, selectedIds.size());

        // Fan out: one activity invocation per event. Worker concurrency
        // cap (set in WorkerMain via setMaxConcurrentActivityExecutionSize)
        // throttles to 2 in flight regardless of how many we fire here.
        List<Promise<Long>> promises = new ArrayList<>(selectedIds.size());
        for (Long selectedId : selectedIds) {
            promises.add(Async.function(narrate::narrate, date, selectedId));
        }

        // Wait for all. Each activity has its own retry; tolerate
        // individual failures so one bad event doesn't kill the run.
        long narrated = 0;
        long failed = 0;
        for (int i = 0; i < promises.size(); i++) {
            try {
                narrated += promises.get(i).get();
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
