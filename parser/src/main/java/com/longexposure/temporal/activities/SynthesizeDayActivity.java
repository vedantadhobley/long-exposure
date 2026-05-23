package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * SYNTHESIZE stage — one LLM call per day that reads every per-event
 * narration + interpretation for the trading date and produces a single
 * paragraph identifying cross-event themes (time-of-day concentration,
 * cross-symbol coherence, sector / ETF-family patterns, regime shifts
 * across the session). Saves to {@code daily_synthesis}.
 *
 * <p>This is the smallest LLM stage by call count (1 per day vs 164 for
 * DESCRIBE / INTERPRET) but the largest by prompt size — the input is
 * ~22K tokens of concatenated narration prose. The 122B Qwen model on
 * joi handles this comfortably within its 262K context window.
 *
 * <p>Runs on the same {@link com.longexposure.temporal.workflows.DailyPipelineWorkflow#NARRATION_TASK_QUEUE}
 * as DESCRIBE / INTERPRET — shares the JVM-wide 2-concurrent LLM-call
 * cap. Only-one-LLM-workflow-at-a-time rule applies.
 *
 * <p>Timeout sizing: ~22K-token prompt + ~500-token completion ≈ 25 sec
 * LLM call. 5 min start-to-close is comfortable headroom.
 */
@ActivityInterface
public interface SynthesizeDayActivity {

    /**
     * Synthesize the day's themes for {@code tradingDate}. Returns 1
     * on successful upsert, 0 if no narrations exist for the date,
     * throws on LLM / DB failure so Temporal retries.
     */
    @ActivityMethod
    long synthesize(LocalDate tradingDate);
}
