package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * Combines temporally-overlapping scored events on the same symbol into
 * a single richer "combined" event. Runs between {@code ScoreEventsActivity}
 * and {@code SelectTopEventsActivity}.
 *
 * <p>Algorithm: per-symbol, sweep events sorted by {@code ts}. Two
 * events combine if their {@code [ts, ts_end]} intervals overlap. The
 * extended cluster's {@code max(ts_end)} keeps growing as overlapping
 * events join, so a halt that lasts 4 min absorbs every cluster event
 * happening during it.
 *
 * <p>Combined events get a new row in {@code scored_events} with
 * {@code scorer_id='combined'}, score = {@code max(constituent_scores)},
 * and a {@code breakdown} JSON nesting the original events in a
 * {@code constituents[]} array. Each constituent row's
 * {@code subsumed_by_event_id} is set to the new combined row's
 * {@code event_id} so {@code SelectTopEventsActivity} skips them.
 *
 * <p>Single-event clusters are no-ops; the existing event stays as-is.
 *
 * <p>Idempotent: pre-cleans any existing {@code combined} rows + clears
 * any existing {@code subsumed_by_event_id} for the date before running.
 *
 * @return number of combined rows written.
 */
@ActivityInterface
public interface CombineRelatedEventsActivity {

    @ActivityMethod
    long combineRelatedEvents(LocalDate tradingDate);
}
