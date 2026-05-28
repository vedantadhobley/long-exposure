package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityExecutionContext;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Auto-closeable background thread that calls
 * {@link ActivityExecutionContext#heartbeat(Object)} at a fixed interval,
 * carrying a mutable "current stage" label so the heartbeat payload reflects
 * what the activity is actually doing.
 *
 * <p>Use case: any activity with a blocking operation that takes longer than
 * the configured heartbeat-timeout — e.g. a multi-minute LLM HTTP call, a
 * long Postgres JOIN with no row yields. Without a background heartbeat,
 * the activity stalls past the heartbeat-timeout and Temporal kills it
 * (this exact bug killed two days' worth of synthesis during the
 * 2026-05-28 morning relaunch).
 *
 * <p>Pattern lifted from {@link MaterializeOrderLifecycleActivityImpl} —
 * extracted here as a shared utility so SynthesizeDay / AggregateWeek /
 * AggregateQuarter / AggregateYear can drop the band-aid heartbeat-timeout
 * bumps (commit {@code 15af21f}) in favor of a real liveness signal.
 *
 * <p>Usage:
 * <pre>
 *   try (BackgroundHeartbeat hb = BackgroundHeartbeat.start(actx, "synth-day", 30)) {
 *       hb.setStage("load");
 *       loadData(...);
 *       hb.setStage("llm:attempt-1");
 *       String text = llama.chat(...);
 *       hb.setStage("verify");
 *       verify(...);
 *   }
 * </pre>
 */
public final class BackgroundHeartbeat implements AutoCloseable {

    private final ScheduledExecutorService exec;
    private final AtomicReference<String> stage;

    private BackgroundHeartbeat(final ScheduledExecutorService exec,
                                 final AtomicReference<String> stage) {
        this.exec = exec;
        this.stage = stage;
    }

    /**
     * Start a background heartbeat thread that calls
     * {@code actx.heartbeat("keep_alive:<stage>")} every {@code intervalSec}
     * seconds. The returned {@link AutoCloseable} stops the thread on close.
     *
     * @param actx        the activity's execution context
     * @param threadName  name for the daemon thread (e.g. {@code "synth-day-heartbeat"})
     * @param intervalSec heartbeat interval in seconds (typically 30 for
     *                    activities with heartbeat-timeout ≥ 1 min)
     */
    public static BackgroundHeartbeat start(final ActivityExecutionContext actx,
                                             final String threadName,
                                             final int intervalSec) {
        AtomicReference<String> stage = new AtomicReference<>("starting");
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
        exec.scheduleAtFixedRate(
                () -> {
                    try {
                        actx.heartbeat("keep_alive:" + stage.get());
                    } catch (Exception ignored) {
                        // Heartbeat exceptions during shutdown are common
                        // and not actionable — swallow.
                    }
                },
                intervalSec, intervalSec, TimeUnit.SECONDS);
        return new BackgroundHeartbeat(exec, stage);
    }

    /** Update the current stage label — appears in the next heartbeat payload. */
    public void setStage(final String s) {
        stage.set(s);
    }

    @Override
    public void close() {
        exec.shutdownNow();
    }
}
