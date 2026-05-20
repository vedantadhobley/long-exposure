package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * Enriches high-scoring "parent" events with deterministic summary
 * stats about same-symbol other-scorer events whose intervals nest
 * within the parent's {@code [ts, ts_end]} window. Runs between
 * {@code ScoreEventsActivity} and {@code SelectTopEventsActivity}.
 *
 * <p><b>The problem this solves</b> (see docs/decisions.md 2026-05-20):
 * market events fire at multiple time scales simultaneously. A long
 * sec-scale event like {@code liquidity_withdrawal} (lasting 11 s)
 * CONTAINS many ms-scale events ({@code post_cancel_cluster},
 * {@code layering}, {@code sweep}) that fire as its mechanism. The
 * nested children are the parent at finer resolution; they aren't
 * separate stories. Narrating them as standalone events floods the
 * day with redundant prose.
 *
 * <p>This activity absorbs nested children into the parent's
 * {@code breakdown.co_occurring} block as summary counts + aggregates
 * (deterministic, scoring-time, no LLM involvement). The children get
 * {@code subsumed_by_event_id} set so {@link SelectTopEventsActivity}
 * skips them.
 *
 * <p><b>What this is NOT</b>:
 * <ul>
 *   <li>Not same-scorer combining — never absorbs an event of the same
 *       {@code scorer_id} (those are different bursts of the same
 *       pattern type, not nested mechanism). Repetition across the day
 *       is handled by Layer 3 daily synthesis.
 *   <li>Not knob-based — no time-window parameter. The parent's own
 *       {@code [ts, ts_end]} duration defines the lookup window.
 *   <li>Not interpretation — the child stats are counts and aggregates,
 *       not "this means X". The LLM narrator describes what's there;
 *       it doesn't infer intent.
 * </ul>
 *
 * <p>Selecting which events become parents: same percentile-rank rule
 * the selection step uses (top 5 % per scorer with floor 1, ceiling 30).
 * Processed in score-descending order so the largest event wins the
 * children when intervals nest within each other.
 *
 * <p>Idempotent: pre-cleans the {@code co_occurring} block from all
 * breakdowns + clears all {@code subsumed_by_event_id} for the date
 * before running.
 *
 * @return number of parent events that absorbed at least one child.
 */
@ActivityInterface
public interface EnrichWithCoOccurrenceActivity {

    @ActivityMethod
    long enrichWithCoOccurrence(LocalDate tradingDate);
}
