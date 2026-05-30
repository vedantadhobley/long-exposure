package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Raw .pcap.gz file TTL — independent of the wire-data retention sweep.
 *
 * <p><b>Why separate from {@link CleanupFilesActivity}.</b> Cleanup deletes
 * the THREE files for the trading date that JUST finished, and only when
 * {@code runRetentionSweep && status==ok}. Two foot-guns:
 * <ol>
 *   <li>Ad-hoc re-runs (most of our work-in-progress runs) pass
 *       {@code runRetentionSweep=false}, so files accumulate indefinitely.
 *   <li>The decision keys off the run's status string, not wall-clock age —
 *       so a failed parse leaves the file behind forever.
 * </ol>
 * Result observed 2026-05-30: 432 GB parked in {@code /storage/raw/}, 14
 * days of files × ~32 GB/day.
 *
 * <p><b>This activity</b> walks {@code IEX_RAW_DIR} (default
 * {@code /storage/raw}), finds every {@code *.pcap.gz} whose embedded date
 * stem ({@code YYYYMMDD_IEXTP1_*}) is older than {@code retainDays}, and
 * deletes them. Wall-clock TTL, no pipeline_run dependency.
 *
 * <p><b>Default policy.</b> Wire data is parsed into Postgres at ingest; we
 * never need the raw file for product purposes after that. We DO need it
 * for: (a) re-validation triangle (DPLS↔DEEP / DPLS→TOPS / DEEP→TOPS,
 * runs once at ingest), (b) re-parse if a parser bug is discovered (a
 * development workflow). IEX HIST is free + always available, ~10 min to
 * re-download. So {@code retainDays=3} is the right default: covers
 * "yesterday's pipeline failed, rerun today" without holding more.
 *
 * <p>Idempotent. Returns files deleted + bytes freed.
 */
@ActivityInterface
public interface RetainRawFilesActivity {

    @ActivityMethod
    RetainResult retain(int retainDays);

    record RetainResult(int filesDeleted, long bytesFreed) {}
}
