package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * Post-select per-event analytics enrichment. Runs AFTER
 * {@link SelectTopEventsActivity} so it only touches the ~90–170
 * narratable events for the day — these are the "compute lazily for
 * selected events" analytics (see {@code docs/analytics-catalog.md} §3.5):
 * metrics that need DB access beyond a scorer's own in-memory cluster
 * (an {@code order_lifecycle} side lookup, a windowed query, or — later —
 * book-state replay) and so don't belong in the whole-day scorer pass.
 *
 * <p>Distinct from {@link EnrichWithCoOccurrenceActivity}: that one runs
 * pre-select over all {@code scored_events} to absorb nested children
 * into a parent's {@code co_occurring} block. This one runs post-select
 * and adds derived <em>measurement</em> fields to each selected event's
 * own {@code breakdown}.
 *
 * <p>Every field is written into {@code selected_events.breakdown} (the
 * narration input set), so both DESCRIBE and INTERPRET see them via the
 * one contract; which field each stage <em>features</em> is the separate
 * narration-set / prompt decision. Window/order-flow context that is
 * inherently neighborhood-shaped (reversion, OFI, surrounding VWAP) lives
 * in {@code InterpretEventActivity} instead — it already fetches the
 * per-event windows.
 *
 * <p>Idempotent: each stat overwrites its own breakdown keys via a
 * shallow {@code jsonb ||} merge, so re-running converges (no pre-clean
 * needed). Events whose inputs are absent are left untouched.
 *
 * <p>Currently computes:
 * <ul>
 *   <li>{@code liquidity_withdrawal} — side-split / two-sidedness of the
 *       cancel burst (bid-side vs ask-side), from {@code order_lifecycle}.
 * </ul>
 * The book-state tier (depth-from-touch, halt pre-event spread,
 * %-of-book-removed + recovery, effective spread) lands here next,
 * reusing the validated {@code OrderBookManager}.
 *
 * @return number of selected events whose breakdown was enriched.
 */
@ActivityInterface
public interface EnrichAnalyticsActivity {

    @ActivityMethod
    long enrichAnalytics(LocalDate tradingDate);
}
