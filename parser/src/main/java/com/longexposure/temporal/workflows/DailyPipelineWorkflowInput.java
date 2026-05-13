package com.longexposure.temporal.workflows;

import java.time.LocalDate;

/**
 * Input for {@link DailyPipelineWorkflow}. Three flags control behavior;
 * each is defaulted differently between cron mode and ad-hoc mode.
 *
 * <p>Cron mode (from the scheduled cron registration in
 * {@code WorkerMain.setupSchedules}):
 * <ul>
 *   <li>{@code pollUntilReady = true} — wait up to 3 hours for files to upload
 *   <li>{@code forceReingest = false} — skip if data already exists
 *   <li>{@code runRetentionSweep = true} — drop old chunks after success
 * </ul>
 *
 * <p>Ad-hoc mode (manually triggered from Temporal UI or via the
 * Temporal client):
 * <ul>
 *   <li>{@code pollUntilReady = false} — fail fast if file isn't there
 *   <li>{@code forceReingest = false} (default; caller can override)
 *   <li>{@code runRetentionSweep = false} — never touch old data
 * </ul>
 *
 * <p>The full rationale and dependency graph are in
 * {@code docs/temporal-design.md}.
 */
public record DailyPipelineWorkflowInput(
        LocalDate targetDate,
        boolean pollUntilReady,
        boolean forceReingest,
        boolean runRetentionSweep) {

    /** Construct an ad-hoc input with the safe defaults (no polling, no retention sweep, no force-reingest). */
    public static DailyPipelineWorkflowInput adHoc(final LocalDate targetDate) {
        return new DailyPipelineWorkflowInput(targetDate, false, false, false);
    }

    /** Construct an ad-hoc input that overrides existing data. */
    public static DailyPipelineWorkflowInput adHocForceReingest(final LocalDate targetDate) {
        return new DailyPipelineWorkflowInput(targetDate, false, true, false);
    }

    /** Construct the cron input that the scheduled fire produces. */
    public static DailyPipelineWorkflowInput cron(final LocalDate targetDate) {
        return new DailyPipelineWorkflowInput(targetDate, true, false, true);
    }
}
