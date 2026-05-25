package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * AGGREGATE stage — one LLM call per week that reads every {@code daily_synthesis}
 * paragraph for the week and produces a single "week of …" themes paragraph.
 * The level above SYNTHESIZE: where SYNTHESIZE finds cross-event themes within
 * one day, AGGREGATE finds cross-day themes across the week (regimes building
 * over several sessions, symbols recurring day-to-day, session-phase drift).
 * Saves to {@code weekly_aggregate}.
 *
 * <p>Smallest LLM stage in the project — one call per week over ~5 short
 * daily-theme paragraphs (~1-2K tokens of input), trivially within joi's
 * context budget. Reuses {@link com.longexposure.narration.SynthesisVerifier}
 * (same grounding contract one level up: tickers ⊆ the week's daily-synthesis
 * tickers, numbers ⊆ the daily syntheses + week aggregates).
 *
 * <p>Runs on {@link com.longexposure.temporal.workflows.DailyPipelineWorkflow#NARRATION_TASK_QUEUE}
 * — shares the JVM-wide 2-concurrent LLM-call cap; only-one-LLM-workflow-at-a-time
 * rule applies.
 */
@ActivityInterface
public interface AggregateWeekActivity {

    /**
     * Aggregate the themes of the ISO week containing {@code anyDateInWeek}.
     * The activity resolves the week to its Monday and reads all
     * {@code daily_synthesis} rows Mon–Sun. Returns 1 on successful upsert,
     * 0 if no daily syntheses exist for the week, throws on LLM / DB failure
     * so Temporal retries.
     */
    @ActivityMethod
    long aggregate(LocalDate anyDateInWeek);
}
