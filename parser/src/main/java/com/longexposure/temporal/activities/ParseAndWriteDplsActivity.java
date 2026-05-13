package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * Parses a DPLS .pcap.gz file and writes its contents to the per-event-type
 * Postgres hypertables via {@link com.longexposure.storage.TimescaleWriter}.
 *
 * <p><b>Idempotent retry safety.</b> First step is a pre-clean: deletes
 * existing DPLS rows for {@code targetDate} across every event table
 * ({@code orders_add}, {@code orders_modify}, {@code orders_delete},
 * {@code orders_executed}, {@code clear_books}, plus the DPLS rows in
 * the shared {@code trades}, {@code trade_breaks}, {@code status_events},
 * {@code retail_liquidity}, {@code securities} tables). Then parses
 * + COPY-writes. If the activity is retried after a partial parse, the
 * pre-clean re-runs on the second attempt and we get a clean re-ingest.
 *
 * <p>Heartbeats every 100K rows written; Temporal's
 * {@code heartbeat_timeout} should be tuned wider than that interval.
 *
 * <p>The {@code force} parameter is a no-op safety knob — the activity
 * always pre-cleans regardless. {@code force} exists for callers that
 * want to make the destructive intent explicit at call time.
 *
 * <p>Returns total rows written across all tables.
 */
@ActivityInterface
public interface ParseAndWriteDplsActivity {

    @ActivityMethod
    long parseAndWrite(String dplsFilePath, LocalDate targetDate, boolean force);
}
