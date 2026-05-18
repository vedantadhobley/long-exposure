package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.RefreshSymbolMetadataActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

public final class RefreshSymbolsWorkflowImpl implements RefreshSymbolsWorkflow {

    private final RefreshSymbolMetadataActivity activity = Workflow.newActivityStub(
            RefreshSymbolMetadataActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(10))
                    .setHeartbeatTimeout(Duration.ofMinutes(2))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(30))
                            .setMaximumInterval(Duration.ofMinutes(5))
                            .setMaximumAttempts(3)
                            .build())
                    .build());

    @Override
    public long run() {
        return activity.refreshSymbolMetadata();
    }
}
