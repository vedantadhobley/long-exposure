package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * Takes three leg results and upserts a row to {@code validation_runs}.
 * Classifies overall status using the same thresholds the legacy
 * {@code ValidateTriangleActivity} used:
 * <ul>
 *   <li>{@code dpls_deep_match_pct} ≥ 0.9999 → load-bearing leg passes
 *   <li>both BBO legs ≥ 0.99 → BBO legs pass
 *   <li>any leg null (activity failed) → {@code failed}
 * </ul>
 */
@ActivityInterface
public interface RecordValidationActivity {

    @ActivityMethod
    Result record(
            LocalDate tradingDate,
            ValidationLegResult dplsDeep,
            ValidationLegResult dplsTops,
            ValidationLegResult deepTops);

    record Result(String status, String notesJson, long elapsedSeconds) {}
}
