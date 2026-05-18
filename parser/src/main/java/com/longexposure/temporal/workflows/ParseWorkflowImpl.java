package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.Feed;
import com.longexposure.temporal.activities.ParseAndWriteDplsActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public final class ParseWorkflowImpl implements ParseWorkflow {

    private static final Logger LOG = Workflow.getLogger(ParseWorkflowImpl.class);

    private static final String RAW_DIR = "/storage/raw";

    private final ParseAndWriteDplsActivity parser = Workflow.newActivityStub(
            ParseAndWriteDplsActivity.class,
            ActivityOptions.newBuilder()
                    // 162 M rows via COPY took 35 min on 2026-05-08; budget
                    // 2 h with a 15 min heartbeat for safety.
                    .setStartToCloseTimeout(Duration.ofHours(2))
                    .setHeartbeatTimeout(Duration.ofMinutes(15))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(30))
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    @Override
    public long run(final Input input) {
        LocalDate date = input.targetDate();
        String dplsPath = RAW_DIR + "/"
                + date.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "_IEXTP1_" + Feed.DPLS.name() + "1.0.pcap.gz";

        LOG.info("parse start  date={} path={} force={}", date, dplsPath, input.forceReingest());
        long messageCount = parser.parseAndWrite(dplsPath, date, input.forceReingest());
        LOG.info("parse done  date={} messages={}", date, messageCount);
        return messageCount;
    }
}
