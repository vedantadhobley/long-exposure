package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * Runs the three correctness validators on the day's raw .pcap.gz files
 * and writes one row to {@code validation_runs}. The three validators
 * are the existing in-tree ones from {@code com.longexposure.validation}:
 *
 * <ul>
 *   <li>{@code DplsBboCrossValidator} — derived BBO from DPLS book vs
 *       every TOPS QuoteUpdate. Expected ≥ 99% match.
 *   <li>{@code DeepBboCrossValidator} — same shape, DEEP-side book. Should
 *       produce identical numbers to DPLS→TOPS (parsers bug-equivalent).
 *   <li>{@code DeepVsDplsValidator} — DPLS↔DEEP price-level direct
 *       comparison. The load-bearing leg; expected 100% (modulo
 *       same-ns multi-txn noise floor at 10⁻⁸).
 * </ul>
 *
 * <p>All three run sequentially in this activity (parallelism inside
 * one activity is overkill; the activity itself runs in parallel with
 * {@link ParseAndWriteDplsActivity} at the workflow level).
 *
 * <p>Returns the {@link Result} record so the workflow can decide
 * whether the run is verified or unverified based on match-rate thresholds.
 */
@ActivityInterface
public interface ValidateTriangleActivity {

    @ActivityMethod
    Result validate(
            String dplsFilePath,
            String deepFilePath,
            String topsFilePath,
            LocalDate targetDate);

    /**
     * Triangle results. Match rates are in [0.0, 1.0]. {@code null} means
     * the validator didn't run (e.g. previous validator crashed); the
     * workflow should treat this as a validation failure.
     */
    record Result(
            Double dplsTopsMatchRate,
            Double deepTopsMatchRate,
            Double dplsDeepMatchRate,
            boolean tradeVolumeMatches,
            long elapsedSeconds,
            String status,                // "passed" | "below_threshold" | "failed"
            String notesJson              // freeform JSON for mismatch counts etc.
    ) {}
}
