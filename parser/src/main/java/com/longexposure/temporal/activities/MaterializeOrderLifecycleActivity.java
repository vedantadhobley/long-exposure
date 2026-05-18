package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * Builds the {@code order_lifecycle} table for a given trading date by
 * pairing each {@code orders_add} row with its terminal event ({@code
 * orders_delete} OR final {@code orders_executed}). Runs once per day,
 * between {@code ParseAndWriteDplsActivity} and {@code ScoreEventsActivity}.
 *
 * <p>Why this is its own activity (rather than inlined into Score): the
 * pairing JOIN over 162 M × 160 M rows is the most expensive query in
 * the daily pipeline. Materializing the result once lets PostCancel +
 * Layering + any future order-lifecycle scorer query a sequential scan
 * on the partial index instead of re-running the JOIN per scorer.
 *
 * <p>Idempotent: pre-cleans {@code order_lifecycle} for the target date
 * before populating, so repeated runs produce a deterministic state.
 *
 * @return number of rows written to {@code order_lifecycle}.
 */
@ActivityInterface
public interface MaterializeOrderLifecycleActivity {

    @ActivityMethod(name = "MaterializeOrderLifecycle")
    long materializeOrderLifecycle(LocalDate tradingDate);
}
