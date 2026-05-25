package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * Week-aligned retention sweep over the heavy wire + derived data. Last
 * activity in the cron workflow; skipped in ad-hoc mode (workflow
 * controls this via the {@code runRetentionSweep} flag).
 *
 * <p><b>Policy (week-aligned, minimum 2 full weeks).</b> Retain the
 * current (possibly partial) week plus {@code retainWeeks} completed
 * weeks. The drop boundary is the Monday of {@code cutoffDate}'s week
 * minus {@code retainWeeks} weeks; everything strictly before that Monday
 * is dropped. With {@code retainWeeks=2} the store holds weeks
 * {current, W-1, W-2}; when the current week closes and a new one opens,
 * the boundary advances and the now-3-weeks-ago week rolls off — so we
 * never dip below 2 fully-complete weeks. See
 * {@link RetentionSweepActivityImpl#weekBoundary(LocalDate, int)}.
 *
 * <p><b>What rolls off</b> (the heavy re-score substrate):
 * <ul>
 *   <li>{@code orders_add/modify/delete/executed}, {@code clear_books},
 *       {@code order_lifecycle} — DPLS-only hypertables, chunk-dropped
 *   <li>{@code trades}, {@code trade_breaks}, {@code quotes},
 *       {@code status_events}, {@code auction_info},
 *       {@code official_prices}, {@code securities},
 *       {@code retail_liquidity} — shared with TOPS, so only DPLS rows
 *       are removed via per-row DELETE (chunk drop would clobber the
 *       TOPS validation oracle)
 *   <li>{@code scored_events}, {@code selected_events} — derived
 *       standard tables, DELETE by {@code trading_date}
 * </ul>
 *
 * <p><b>What is kept indefinitely</b> (the product / visible archive):
 * {@code narratives}, {@code interpretations}, {@code daily_synthesis},
 * {@code symbols}, {@code pipeline_runs}, {@code validation_runs}. These
 * are kilobytes/day; dropping the wire substrate only costs the ability
 * to <em>re-score</em> days older than the window, never the narrative
 * archive (which grows one day at a time, forever).
 *
 * <p>Idempotent: re-running on the same {@code cutoffDate} is a no-op
 * ({@code drop_chunks} skips already-dropped chunks; the DELETEs match
 * nothing). Per-table failures are logged, not fatal — the next sweep
 * catches up.
 *
 * <p>Returns chunks dropped + rows deleted, summed across tables.
 */
@ActivityInterface
public interface RetentionSweepActivity {

    @ActivityMethod
    SweepResult sweep(LocalDate cutoffDate, int retainWeeks);

    record SweepResult(int chunksDropped, long rowsDeletedFromSharedTables) {}
}
