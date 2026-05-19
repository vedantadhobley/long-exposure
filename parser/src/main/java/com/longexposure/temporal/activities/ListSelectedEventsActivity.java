package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;
import java.util.List;

/**
 * Returns the list of {@code selected_id}s for a given trading date,
 * ordered by {@code (scorer_id, narration_rank)} so downstream
 * fan-out preserves a stable, top-first iteration order.
 *
 * <p>Exists as a tiny activity because workflow code in Temporal can't
 * touch JDBC directly — every DB read has to go through an activity
 * even if it's a 1-second SELECT.
 */
@ActivityInterface
public interface ListSelectedEventsActivity {

    @ActivityMethod
    List<Long> list(LocalDate tradingDate);
}
