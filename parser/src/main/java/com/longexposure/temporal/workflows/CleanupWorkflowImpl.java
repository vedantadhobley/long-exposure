package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.CleanupFilesActivity;
import com.longexposure.temporal.activities.Feed;
import com.longexposure.temporal.activities.RetentionSweepActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class CleanupWorkflowImpl implements CleanupWorkflow {

    private static final Logger LOG = Workflow.getLogger(CleanupWorkflowImpl.class);

    private static final String RAW_DIR = "/storage/raw";

    private final CleanupFilesActivity cleanup = Workflow.newActivityStub(
            CleanupFilesActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                    .build());

    private final RetentionSweepActivity retention = Workflow.newActivityStub(
            RetentionSweepActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(15))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(30))
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    @Override
    public void run(final Input input) {
        LocalDate date = input.targetDate();
        LOG.info("cleanup start  date={} deleteFiles={} retention={}w",
                date, input.deleteFiles(), input.retentionWeeks());

        if (input.deleteFiles()) {
            String stem = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            cleanup.cleanup(List.of(
                    RAW_DIR + "/" + stem + "_IEXTP1_" + Feed.DPLS.name() + "1.0.pcap.gz",
                    RAW_DIR + "/" + stem + "_IEXTP1_" + Feed.DEEP.name() + "1.0.pcap.gz",
                    RAW_DIR + "/" + stem + "_IEXTP1_" + Feed.TOPS.name() + "1.6.pcap.gz"));
        }

        if (input.runRetentionSweep()) {
            try {
                retention.sweep(date, input.retentionWeeks());
            } catch (ActivityFailure af) {
                // Retention failure isn't fatal — the pipeline already
                // succeeded; we just have extra-old chunks to clean
                // up next time.
                LOG.warn("retention sweep failed (workflow continues)  err={}", af.getMessage());
            }
        }

        LOG.info("cleanup done  date={}", date);
    }
}
