package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * Collapse superseded narration/interpretation rows down to the latest
 * verifier-passing row per content-key.
 *
 * <p><b>Why this exists.</b> {@code narratives} and {@code interpretations}
 * are keyed by content hash ({@code event_hash} / {@code interpretation_hash}).
 * Re-runs (prompt iterations, re-scores, breakdown enrichment, verifier-
 * driven retries that change inputs) insert a new row per changed hash and
 * leave the prior version behind. Steady-state daily operation produces zero
 * orphans; they accumulate only when re-runs touch already-narrated days.
 *
 * <p><b>Keep rule.</b> For each content-key
 * {@code (trading_date, symbol, event_type, event_ts)}, retain exactly one row:
 * the latest passing one (or, if none ever passed, the latest one overall — we
 * never delete an event's only narration). Delete every other row in the
 * partition.
 *
 * <p><b>Why reachability-free.</b> The earlier ad-hoc SQL keyed prune
 * decisions off {@code selected_events}, which works for current data but
 * destroys the narrative archive once retention drops the wire substrate
 * (selected_events is in the retention sweep). This activity preserves every
 * (trading_date, symbol, event_type, event_ts) coordinate that ever produced
 * a narration, whether or not selected_events still has the join key.
 *
 * <p><b>Wiring.</b> Runs as the LAST step of {@link FinalizeDayWorkflow} so
 * every successful per-day pipeline auto-prunes its own re-narration churn.
 * Also independently invokable for backfill cleanup.
 *
 * <p>Idempotent. Returns rows deleted per table.
 */
@ActivityInterface
public interface PruneStaleNarrationsActivity {

    /**
     * Prune across the full table (no date filter). Use after a backfill
     * that may have touched arbitrary historical dates.
     */
    @ActivityMethod
    PruneResult pruneAll();

    /**
     * Prune limited to one trading date. Cheap; the standard per-day
     * FinalizeDay hook uses this form.
     */
    @ActivityMethod
    PruneResult pruneDate(LocalDate tradingDate);

    record PruneResult(long narrativesDeleted, long interpretationsDeleted) {}
}
