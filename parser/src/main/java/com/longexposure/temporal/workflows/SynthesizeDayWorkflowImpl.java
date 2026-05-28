package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.SynthesizeDayActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.LocalDate;

public final class SynthesizeDayWorkflowImpl implements SynthesizeDayWorkflow {

    private static final Logger LOG = Workflow.getLogger(SynthesizeDayWorkflowImpl.class);

    private final SynthesizeDayActivity synthesize = Workflow.newActivityStub(
            SynthesizeDayActivity.class,
            ActivityOptions.newBuilder()
                    // Dispatch on the narration task queue (shares JVM-wide
                    // LLM concurrency with DESCRIBE and INTERPRET).
                    .setTaskQueue(DailyPipelineWorkflow.NARRATION_TASK_QUEUE)
                    // 10 min, not 5 — the activity does up to MAX_LLM_ATTEMPTS=3
                    // verifier-driven retries × ~90s LLM call each ≈ 4.5 min, so a
                    // single transient verifier rejection on attempt 1 eats all the
                    // headroom under a 5-min cap. That caused both 05-15 and 05-18
                    // whole-day synth failures during the 2026-05-28 relaunch. 10 min
                    // gives ~5 min retry headroom while still bounding runaway.
                    .setStartToCloseTimeout(Duration.ofMinutes(10))
                    // 5 min, not 1 — the activity makes a blocking LLM HTTP
                    // call per attempt that can run 30-90s on its own, with
                    // no heartbeat emitted during the wait. A 1-min heartbeat
                    // timeout fires before the LLM even returns. That's what
                    // killed the 05-15 synth recovery at 17:17:55 (started at
                    // 17:16:07, no heartbeat in the next 1:48 = killed). The
                    // proper fix is a background-heartbeat daemon inside the
                    // activity (see MaterializeOrderLifecycleActivityImpl);
                    // for now, give the heartbeat enough room that a single
                    // worst-case LLM call doesn't trip it.
                    .setHeartbeatTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(15))
                            .setMaximumAttempts(2)
                            .build())
                    .build());

    @Override
    public long run(final LocalDate date) {
        LOG.info("synthesize start  date={}", date);
        long result = synthesize.synthesize(date);
        LOG.info("synthesize done  date={} written={}", date, result);
        return result;
    }
}
