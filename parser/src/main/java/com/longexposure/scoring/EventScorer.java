package com.longexposure.scoring;

import java.util.stream.Stream;

/**
 * Extension point for the scoring layer. One class per pattern; see
 * {@code com.longexposure.scoring.scorers.*} for the catalog.
 *
 * <p>An {@code EventScorer} reads raw events from the hypertables for a
 * single trading date and emits zero or more {@link ScoredEvent}s. The
 * {@code breakdown} on each scored event is the grounding contract with
 * the LLM narrator — every claim in eventual narration must trace to a
 * field in {@code breakdown}.
 *
 * <p>Two flavors today:
 * <ul>
 *   <li><b>Intraday</b> — input is one day's data; no historical context.
 *       Halts, large trades, sweeps, layering, post-cancel clusters,
 *       icebergs, liquidity withdrawal.
 *   <li><b>Interday</b> — also reads from a baseline source (cagg / view).
 *       Volume × average, time-in-book drift. Deferred to a later sprint;
 *       v1 is intraday-only.
 * </ul>
 *
 * <p>Implementations should be stateless and safe to re-run on the same
 * date — the scoring activity pre-cleans {@code scored_events} for the
 * date before invoking any scorer.
 */
public interface EventScorer {

    /**
     * Stable identifier used as {@code scored_events.scorer_id}, e.g.
     * {@code "halt"}, {@code "large_trade"}, {@code "sweep"}. Treat as a
     * URI-safe slug — narration prompts and any downstream lookups key
     * on it.
     */
    String id();

    /**
     * Scan source data for the day and emit zero or more scored events.
     * The stream is consumed once by {@link com.longexposure.temporal.activities.ScoreEventsActivity}
     * and written to {@code scored_events} via COPY.
     */
    Stream<ScoredEvent> score(ScoringContext ctx);
}
