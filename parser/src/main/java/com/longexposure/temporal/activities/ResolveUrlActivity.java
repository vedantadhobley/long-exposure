package com.longexposure.temporal.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.time.LocalDate;

/**
 * Resolves the IEX HIST download URL for a specific (trading date, feed).
 * Hits {@code https://iextrading.com/api/1.0/hist?date=YYYYMMDD} once
 * per call.
 *
 * <p>Retry semantics depend on the workflow's {@code pollUntilReady} flag,
 * which is passed through via the workflow's
 * {@code ActivityOptions.retryOptions} at call time:
 * <ul>
 *   <li>{@code pollUntilReady=true} (cron): {@code FilesNotReady} is
 *       retriable with 15-min interval × 3-hr total budget.
 *   <li>{@code pollUntilReady=false} (ad-hoc): {@code FilesNotReady} is
 *       in the workflow's {@code nonRetryableErrorTypes} list, so the
 *       workflow fails fast without retrying.
 * </ul>
 *
 * <p>{@code NotATradingDay} is always non-retriable — the workflow
 * catches it and exits cleanly with status {@code skipped_no_data}.
 *
 * <p>Transient errors ({@link java.io.IOException}, HTTP 5xx) are always
 * retriable on a short backoff regardless of mode.
 */
@ActivityInterface
public interface ResolveUrlActivity {

    @ActivityMethod
    String resolveUrl(LocalDate tradingDate, Feed feed);
}
