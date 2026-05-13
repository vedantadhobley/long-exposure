package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Runs every {@code EventScorer} from
 * {@link com.longexposure.scoring.EventScorerRegistry#ALL} against the
 * day's data and writes the results to {@code scored_events}.
 *
 * <p>Idempotent: first step is a pre-clean
 * ({@code DELETE FROM scored_events WHERE trading_date=?}) so re-runs
 * produce a deterministic table state. Pre-clean is cheap — typical
 * day's scored_events output is in the thousands, not millions.
 *
 * <p>For v1 scorers run sequentially. Each scorer's output is COPY-written
 * to scored_events in a single batch. If a scorer throws, the activity
 * fails the workflow — the workflow's retry policy decides what to do.
 *
 * @return total scored_events rows written across all scorers.
 */
@ActivityInterface
public interface ScoreEventsActivity {

    @ActivityMethod
    long scoreEvents(LocalDate tradingDate, UUID pipelineRunId);
}
