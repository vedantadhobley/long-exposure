package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * Compress any uncompressed TimescaleDB chunks whose time range overlaps
 * the given trading date. Runs as the final processing step of
 * {@link com.longexposure.temporal.workflows.DailyPipelineWorkflow} —
 * after Synthesize, before Cleanup — so each day's wire data is
 * compressed in-place as soon as the day's analytical work is done.
 *
 * <p>Why this exists alongside {@code add_compression_policy(..., INTERVAL '1 day')}
 * configured in {@code schema.sql}: the policy runs on TimescaleDB's
 * internal job scheduler (default daily). Without this activity, a day's
 * ~230 GB of uncompressed data sits on disk until the next scheduled
 * policy run — and during a multi-day backfill, that means 5 × 230 GB
 * = ~1.2 TB peak disk before the policy catches up. The activity
 * compresses immediately after ingest so steady-state disk use stays
 * ~13 GB per day from minute one.
 *
 * <p>The schema.sql policy stays as a steady-state safety net: any
 * chunks the activity missed (e.g., on an activity failure) get caught
 * by the policy's next daily run.
 *
 * <p>Idempotent — chunks already compressed are skipped. Activity-level
 * heartbeats fire between each compress_chunk call so long compressions
 * (the 75 GB {@code order_lifecycle} chunk takes ~7 min) don't trip the
 * heartbeat timeout.
 */
@ActivityInterface
public interface CompressChunksActivity {

    /**
     * Compress every uncompressed chunk on every compression-enabled
     * hypertable whose time range overlaps {@code tradingDate}.
     *
     * @return count of chunks compressed in this call
     */
    @ActivityMethod
    long compress(LocalDate tradingDate);
}
