package com.longexposure.temporal.activities;

/**
 * Thrown by {@link ResolveUrlActivity} when the IEX HIST listing has an
 * entry for the requested date but is missing the requested feed (e.g.
 * DEEP and DPLS have uploaded but TOPS hasn't yet).
 *
 * <p>Retriable in <strong>cron mode</strong> ({@code pollUntilReady=true}):
 * the workflow's retry policy waits 15 min and tries again, up to a
 * total 3-hour budget. After the budget exhausts, the workflow fails
 * the activity loudly — that's a real anomaly (a feed uploaded on every
 * other trading day failed to upload on this one).
 *
 * <p>Non-retriable in <strong>ad-hoc mode</strong> ({@code pollUntilReady=false}):
 * the workflow adds this exception type to its
 * {@code nonRetryableErrorTypes}, so the workflow fails immediately.
 * The caller (typically a human triggering from Temporal UI) sees the
 * error directly.
 */
public class FilesNotReady extends RuntimeException {
    public FilesNotReady(final String message) {
        super(message);
    }
}
