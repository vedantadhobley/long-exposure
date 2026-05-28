package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.AggregateWeekActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.LocalDate;

public final class AggregateWeekWorkflowImpl implements AggregateWeekWorkflow {

    private static final Logger LOG = Workflow.getLogger(AggregateWeekWorkflowImpl.class);

    private final AggregateWeekActivity aggregate = Workflow.newActivityStub(
            AggregateWeekActivity.class,
            ActivityOptions.newBuilder()
                    // Dispatch on the narration task queue (shares the JVM-wide
                    // LLM concurrency cap with DESCRIBE / INTERPRET / SYNTHESIZE).
                    .setTaskQueue(DailyPipelineWorkflow.NARRATION_TASK_QUEUE)
                    // 10 min, not 5 — same reasoning as SynthesizeDayWorkflowImpl.
                    // The activity does up to MAX_LLM_ATTEMPTS=3 verifier-driven
                    // retries × ~90s LLM call each ≈ 4.5 min; a single transient
                    // verifier rejection on attempt 1 eats all the headroom under
                    // a 5-min cap. AggregateQuarter/Year were already 10 min from
                    // their first build — propagating the same value here.
                    .setStartToCloseTimeout(Duration.ofMinutes(10))
                    // 1-min heartbeat — the activity uses BackgroundHeartbeat
                    // (Phase 7b) to call actx.heartbeat() every 30s while the
                    // blocking LLM HTTP call runs. Real liveness signal, not
                    // a band-aid. Replaces the 5-min workaround from 15af21f.
                    .setHeartbeatTimeout(Duration.ofMinutes(1))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(15))
                            .setMaximumAttempts(2)
                            .build())
                    .build());

    @Override
    public long run(final LocalDate anyDateInWeek) {
        LOG.info("aggregate start  anyDateInWeek={}", anyDateInWeek);
        long result = aggregate.aggregate(anyDateInWeek);
        LOG.info("aggregate done  anyDateInWeek={} written={}", anyDateInWeek, result);
        return result;
    }
}
