package com.longexposure.temporal.workflows;

/**
 * Return value of {@link DownloadWorkflow}. Carries the three on-disk
 * paths the downstream phases (parse, validate) read from. Records the
 * total bytes downloaded for telemetry.
 *
 * <p>If the date is not a trading day, the workflow throws
 * {@code ApplicationFailure} typed as {@code NotATradingDay} — the
 * parent catches and short-circuits to {@code skipped_no_data}.
 */
public record DownloadResult(
        String dplsPath,
        String deepPath,
        String topsPath,
        long   totalBytes
) {}
