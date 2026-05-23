package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * Interpret ONE selected event — INTERPRET stage of the pipeline.
 *
 * <p>Pulls the event by {@code selectedId} from {@code selected_events},
 * queries the ±60-sec pre/post trade windows on the same symbol,
 * makes one LLM call to produce 1-2 sentences of sequential/causal
 * narrative context, runs the {@link com.longexposure.narration.InterpretationVerifier}
 * pure-code check, and upserts the result into {@code interpretations}.
 *
 * <p>Runs on the same {@code NARRATION_TASK_QUEUE} as
 * {@link NarrateEventActivity} — both gate on the {@code llama-large.joi}
 * single-GPU bottleneck. The worker's
 * {@code setMaxConcurrentActivityExecutionSize(2)} setting + the
 * JVM-wide {@code Semaphore(2, fair)} inside
 * {@link com.longexposure.llm.LlamaClient} cap concurrency across both
 * activity types. The operational rule "never two LLM-bearing
 * workflows concurrently" remains enforced at the workflow level.
 *
 * <p>Timeout sizing: each event = 2 small SQL queries (pre + post
 * window) + 1 LLM call (~10-15 sec at 2-concurrent on joi). 5 min
 * start-to-close is comfortable headroom.
 */
@ActivityInterface
public interface InterpretEventActivity {

    /**
     * Interpret the event identified by {@code selectedId} for the
     * given trading date. Returns 1 on a successful upsert, 0 on a
     * recoverable miss (event not found), throws on LLM / DB failure
     * so Temporal can retry.
     */
    @ActivityMethod
    long interpret(LocalDate tradingDate, long selectedId);
}
