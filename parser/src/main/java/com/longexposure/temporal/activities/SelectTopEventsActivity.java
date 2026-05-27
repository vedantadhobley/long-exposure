package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * Per-scorer top-N pull from {@code scored_events} into
 * {@code selected_events}. Runs after {@link ScoreEventsActivity}; the
 * narration layer reads only from {@code selected_events}.
 *
 * <p>Selection is by <b>within-scorer percentile rank</b>, not a hardcoded
 * per-scorer cap (the old {@code PER_SCORER_CAPS} map is gone): rank each
 * scorer's events by score and take {@code clamp(round(pct × count), FLOOR=1,
 * CEILING=30)}. This is scorer-agnostic — halts/large-trades (few, high-quality)
 * and pattern scorers (many, mixed) self-balance without per-scorer tuning, and
 * a newly-registered scorer is picked up automatically with no change here.
 *
 * <p>Idempotent: deletes existing rows for {@code tradingDate} before
 * inserting, so re-runs produce a deterministic table state.
 *
 * @return total selected_events rows written.
 */
@ActivityInterface
public interface SelectTopEventsActivity {

    @ActivityMethod
    long selectTopEvents(LocalDate tradingDate);
}
