package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * Narrate ONE selected event. Pulls the event by {@code selectedId}
 * from {@code selected_events}, runs the two-pass extract → render →
 * verify pipeline, and upserts the result into {@code narratives}.
 *
 * <p>Wraps the LLM-bound work for a single event so the orchestrating
 * workflow can fan out 90 of these concurrently. The worker's
 * {@code setMaxConcurrentActivityExecutionSize(2)} setting on this
 * activity type caps Temporal-level parallelism to match the
 * {@code llama-large.joi} single-GPU constraint. The JVM-wide
 * {@code Semaphore(2, fair)} inside {@link com.longexposure.llm.LlamaClient}
 * is the second safety net.
 *
 * <p>Activity timeout sizing: each event takes ~16 sec for two LLM
 * calls. We budget 5 min start-to-close to absorb tail latency on
 * busy days.
 */
@ActivityInterface
public interface NarrateEventActivity {

    /**
     * Narrate the event identified by {@code selectedId} for the given
     * trading date. Returns 1 on a successful upsert, 0 on a failure
     * the activity chose to swallow (so the workflow can keep counting
     * progress).
     */
    @ActivityMethod
    long narrate(LocalDate tradingDate, long selectedId);
}
