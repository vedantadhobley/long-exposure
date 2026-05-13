package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.DeepTopsValidatorActivity;
import com.longexposure.temporal.activities.DplsDeepValidatorActivity;
import com.longexposure.temporal.activities.DplsTopsValidatorActivity;
import com.longexposure.temporal.activities.RecordValidationActivity;
import com.longexposure.temporal.activities.ValidationLegResult;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Standalone validate-only workflow. Runs the three triangle legs concurrently
 * against pcap.gz files already on disk and upserts the result to
 * {@code validation_runs}.
 */
public final class ValidateOnlyWorkflow {

    @WorkflowInterface
    public interface Iface {
        String TASK_QUEUE = "long-exposure-daily-pipeline";

        @WorkflowMethod
        String run(LocalDate targetDate);
    }

    public static final class Impl implements Iface {

        private static final Logger LOG = Workflow.getLogger(Impl.class);
        private static final String RAW_DIR = "/storage/raw";

        private static ActivityOptions legOptions() {
            return ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                    .build();
        }

        private final DplsDeepValidatorActivity dplsDeepLeg =
                Workflow.newActivityStub(DplsDeepValidatorActivity.class, legOptions());
        private final DplsTopsValidatorActivity dplsTopsLeg =
                Workflow.newActivityStub(DplsTopsValidatorActivity.class, legOptions());
        private final DeepTopsValidatorActivity deepTopsLeg =
                Workflow.newActivityStub(DeepTopsValidatorActivity.class, legOptions());
        private final RecordValidationActivity recordValidation = Workflow.newActivityStub(
                RecordValidationActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofMinutes(2))
                        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                        .build());

        @Override
        public String run(final LocalDate date) {
            String stem = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String dpls = RAW_DIR + "/" + stem + "_IEXTP1_DPLS1.0.pcap.gz";
            String deep = RAW_DIR + "/" + stem + "_IEXTP1_DEEP1.0.pcap.gz";
            String tops = RAW_DIR + "/" + stem + "_IEXTP1_TOPS1.6.pcap.gz";

            LOG.info("validate-only start  date={} (parallel)", date);

            Promise<ValidationLegResult> p1 = Async.function(dplsDeepLeg::validate, dpls, deep);
            Promise<ValidationLegResult> p2 = Async.function(dplsTopsLeg::validate, dpls, tops);
            Promise<ValidationLegResult> p3 = Async.function(deepTopsLeg::validate, deep, tops);

            ValidationLegResult r1 = tryGet(p1, "DPLS↔DEEP");
            ValidationLegResult r2 = tryGet(p2, "DPLS→TOPS");
            ValidationLegResult r3 = tryGet(p3, "DEEP→TOPS");

            RecordValidationActivity.Result rec = recordValidation.record(date, r1, r2, r3);
            LOG.info("validate-only done  date={} status={}", date, rec.status());
            return rec.status();
        }

        private static ValidationLegResult tryGet(final Promise<ValidationLegResult> p, final String legName) {
            try {
                return p.get();
            } catch (ActivityFailure af) {
                Workflow.getLogger(Impl.class).error("validator leg failed  leg={} err={}",
                        legName, af.getMessage());
                return null;
            }
        }
    }
}
