package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * Reads every row in {@code selected_events} for the trading date and
 * runs the two-pass + verify narration pipeline against each, writing
 * results to {@code narratives}.
 *
 * <p>Single activity (not one-activity-per-event) for v1 because:
 * <ul>
 *   <li>LLM concurrency is capped at 2 by {@code LlamaClient}'s semaphore
 *       — fan-out doesn't help past that limit.
 *   <li>Sequential per-event is ~10s × 90 events = 15 min wall-clock,
 *       comfortable.
 *   <li>One activity is easier to retry / debug than 90 child workflows.
 * </ul>
 *
 * <p>Idempotent: per-event cache uses {@code event_hash} as the
 * {@code narratives} primary key. Re-running with unchanged prompts +
 * breakdowns is a no-op (UPSERT semantics).
 *
 * @return total narratives written/updated.
 */
@ActivityInterface
public interface NarrateEventsActivity {

    @ActivityMethod
    long narrateEvents(LocalDate tradingDate);
}
