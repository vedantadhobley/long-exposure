package com.longexposure.temporal.activities;

/**
 * Thrown by {@link ResolveUrlActivity} when the IEX HIST listing has no
 * entry for the requested date. <strong>Always non-retriable.</strong>
 * The workflow catches this and exits cleanly with status
 * {@code skipped_no_data}.
 *
 * <p>"No entry" is the indistinguishable signal from "weekend",
 * "holiday", and "real IEX outage on a real trading day." In the cron
 * path, the workflow's retry policy will exhaust its 3-hour polling
 * budget before this gets thrown — at which point we treat it as the
 * benign no-data case.
 */
public class NotATradingDay extends RuntimeException {
    public NotATradingDay(final String message) {
        super(message);
    }
}
