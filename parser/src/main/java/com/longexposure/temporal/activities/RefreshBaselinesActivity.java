package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * Refreshes the {@code daily_volume_by_symbol} continuous aggregate for one
 * trading day's bucket, so the inter-day scorers (notably
 * {@link com.longexposure.scoring.scorers.VolumeDeviationScorer}) read a cagg
 * that already includes the just-parsed day. Runs as the first step of
 * {@code ScoreWorkflow}, before {@code MaterializeOrderLifecycleActivity}.
 *
 * <p>Why this exists: the cagg is otherwise refreshed only by its hourly
 * background policy. In a nightly run, Score fires minutes after Parse — long
 * before the hourly policy materializes today's trades — so without an
 * explicit refresh the scorer would read a cagg with no row for today and emit
 * nothing. This activity makes cagg-freshness an explicit, observable pipeline
 * step rather than a timing-dependent side effect. It also fires on every
 * ad-hoc {@code ScoreWorkflow} re-run, so manual re-scores are correct too.
 *
 * <p>Only the single day's bucket is refreshed: prior days' buckets are
 * already materialized and their source rows are immutable (and eventually
 * dropped by retention — the cagg keeps the materialized result). Idempotent —
 * {@code refresh_continuous_aggregate} on an up-to-date window is a no-op.
 * Must run outside an explicit transaction, so the impl uses an autocommit
 * connection (not the scoring activity's cursor transaction).
 *
 * @return 1 on success (the activity is a fixed single-day refresh).
 */
@ActivityInterface
public interface RefreshBaselinesActivity {

    @ActivityMethod(name = "RefreshBaselines")
    long refreshBaselines(LocalDate tradingDate);
}
