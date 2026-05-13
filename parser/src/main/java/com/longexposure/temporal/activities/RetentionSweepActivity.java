package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * Drops Postgres chunks older than {@code cutoffDate - retainDays} from
 * every DPLS-affected hypertable. Last activity in the cron workflow.
 * Skipped in ad-hoc mode (workflow controls this via the
 * {@code runRetentionSweep} flag).
 *
 * <p>Idempotent: re-running on the same {@code cutoffDate} is a no-op
 * (TimescaleDB's {@code drop_chunks} silently skips already-dropped
 * chunks).
 *
 * <p>Affected tables — all data with {@code feed_source='DPLS'}:
 * <ul>
 *   <li>{@code orders_add}, {@code orders_modify}, {@code orders_delete},
 *       {@code orders_executed}, {@code clear_books} (DPLS-only)
 *   <li>{@code trades}, {@code trade_breaks}, {@code status_events},
 *       {@code retail_liquidity}, {@code securities} (shared with TOPS;
 *       only DPLS rows are dropped via per-table-row DELETE since
 *       chunk-level drop would clobber TOPS rows too)
 * </ul>
 *
 * <p>The shared tables have a slower per-row DELETE path on the
 * retention sweep; that's intentional — clobbering TOPS data would
 * lose the validation oracle. In practice, the DPLS-only tables hold
 * 99%+ of the data, so chunk-level drop on those handles the bulk.
 *
 * <p>Failure to drop a chunk is logged but doesn't fail the activity;
 * the next sweep will catch up.
 *
 * <p>Returns chunks dropped + rows deleted, summed across tables.
 */
@ActivityInterface
public interface RetentionSweepActivity {

    @ActivityMethod
    SweepResult sweep(LocalDate cutoffDate, int retainDays);

    record SweepResult(int chunksDropped, long rowsDeletedFromSharedTables) {}
}
