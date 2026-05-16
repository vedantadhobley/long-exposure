package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * Per-scorer top-N pull from {@code scored_events} into
 * {@code selected_events}. Runs after {@link ScoreEventsActivity}; the
 * narration layer reads only from {@code selected_events}.
 *
 * <p>Per-scorer caps are hardcoded in
 * {@link SelectTopEventsActivityImpl#PER_SCORER_CAPS}. Tunable per scorer
 * because halts and large trades produce few high-quality events
 * (10-100 per day) while pattern scorers produce many low-quality
 * events (10K-100K per day) — they need different cutoffs to keep the
 * eventual narration set balanced.
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
