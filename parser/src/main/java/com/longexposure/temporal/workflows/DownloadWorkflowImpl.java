package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.DownloadFileActivity;
import com.longexposure.temporal.activities.Feed;
import com.longexposure.temporal.activities.FilesNotReady;
import com.longexposure.temporal.activities.NotATradingDay;
import com.longexposure.temporal.activities.ResolveUrlActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public final class DownloadWorkflowImpl implements DownloadWorkflow {

    private static final Logger LOG = Workflow.getLogger(DownloadWorkflowImpl.class);

    private static final String RAW_DIR = "/storage/raw";

    /**
     * URL resolution. Retry policy is set per-invocation because the
     * polling vs non-polling behavior differs between cron and ad-hoc.
     */
    private static ResolveUrlActivity resolveStub(final boolean pollUntilReady) {
        RetryOptions retry = pollUntilReady
                ? RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofMinutes(15))
                        .setMaximumInterval(Duration.ofMinutes(15))
                        .setBackoffCoefficient(1.0)
                        .setDoNotRetry(NotATradingDay.class.getName())
                        .build()
                : RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofSeconds(15))
                        .setMaximumAttempts(3)
                        .setDoNotRetry(NotATradingDay.class.getName(), FilesNotReady.class.getName())
                        .build();

        Duration scheduleToClose = pollUntilReady ? Duration.ofHours(3) : Duration.ofMinutes(1);

        return Workflow.newActivityStub(
                ResolveUrlActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(30))
                        .setScheduleToCloseTimeout(scheduleToClose)
                        .setRetryOptions(retry)
                        .build());
    }

    private final DownloadFileActivity downloader = Workflow.newActivityStub(
            DownloadFileActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofHours(1))
                    .setHeartbeatTimeout(Duration.ofMinutes(2))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(30))
                            .setMaximumAttempts(5)
                            .setDoNotRetry(NotATradingDay.class.getName())
                            .build())
                    .build());

    @Override
    public DownloadResult run(final Input input) {
        final LocalDate date = input.targetDate();
        LOG.info("download start  date={} poll={}", date, input.pollUntilReady());

        ResolveUrlActivity resolve = resolveStub(input.pollUntilReady());
        Promise<String> dplsUrlP = Async.function(resolve::resolveUrl, date, Feed.DPLS);
        Promise<String> deepUrlP = Async.function(resolve::resolveUrl, date, Feed.DEEP);
        Promise<String> topsUrlP = Async.function(resolve::resolveUrl, date, Feed.TOPS);

        String dplsUrl, deepUrl, topsUrl;
        try {
            dplsUrl = dplsUrlP.get();
            deepUrl = deepUrlP.get();
            topsUrl = topsUrlP.get();
        } catch (ActivityFailure af) {
            if (isCause(af, NotATradingDay.class.getName())) {
                LOG.info("not a trading day  date={}", date);
                throw ApplicationFailure.newFailure(
                        "Not a trading day: " + date, NotATradingDay.class.getName());
            }
            throw af;
        }

        String dplsPath = pathFor(date, Feed.DPLS);
        String deepPath = pathFor(date, Feed.DEEP);
        String topsPath = pathFor(date, Feed.TOPS);

        Promise<String> dplsDownP = Async.function(downloader::downloadFile, dplsUrl, dplsPath);
        Promise<String> deepDownP = Async.function(downloader::downloadFile, deepUrl, deepPath);
        Promise<String> topsDownP = Async.function(downloader::downloadFile, topsUrl, topsPath);

        // Wait for all three. Any failure propagates as ActivityFailure.
        Promise.allOf(dplsDownP, deepDownP, topsDownP).get();

        LOG.info("download done  date={}", date);
        return new DownloadResult(dplsPath, deepPath, topsPath, 0L);
    }

    private static String pathFor(final LocalDate date, final Feed feed) {
        String version = switch (feed) {
            case TOPS -> "1.6";
            case DEEP -> "1.0";
            case DPLS -> "1.0";
        };
        return RAW_DIR + "/" + date.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "_IEXTP1_" + feed.name() + version + ".pcap.gz";
    }

    private static boolean isCause(final ActivityFailure af, final String type) {
        Throwable t = af;
        while (t != null) {
            if (t instanceof ApplicationFailure appF && type.equals(appF.getType())) return true;
            t = t.getCause();
        }
        return false;
    }
}
