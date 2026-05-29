package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.NotATradingDay;
import com.longexposure.temporal.activities.PipelineRunRecorderActivity;
import com.longexposure.temporal.activities.RecordValidationActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Luv-side pre-LLM chain for one trading day. Stage 3 — extracted from
 * {@link DailyPipelineWorkflowImpl} so the chain is independently composable.
 *
 * <p>Returns the {@code pipeline_runs.status} the run completed at.
 */
public final class IngestDayWorkflowImpl implements IngestDayWorkflow {

    private static final Logger LOG = Workflow.getLogger(IngestDayWorkflowImpl.class);
    private static final LocalDate PLACEHOLDER_DATE = LocalDate.of(1970, 1, 1);

    private final PipelineRunRecorderActivity recorder = Workflow.newActivityStub(
            PipelineRunRecorderActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(transientRetry(3))
                    .build());

    private static ChildWorkflowOptions childOptions(final String phase) {
        return ChildWorkflowOptions.newBuilder()
                .setWorkflowId(Workflow.getInfo().getWorkflowId() + "-" + phase)
                .setTaskQueue(DailyPipelineWorkflow.TASK_QUEUE)
                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_TERMINATE)
                .build();
    }

    private final DownloadWorkflow downloadChild = Workflow.newChildWorkflowStub(
            DownloadWorkflow.class, childOptions("download"));
    private final ParseWorkflow parseChild = Workflow.newChildWorkflowStub(
            ParseWorkflow.class, childOptions("parse"));
    private final ValidateWorkflow validateChild = Workflow.newChildWorkflowStub(
            ValidateWorkflow.class, childOptions("validate"));
    private final ScoreWorkflow scoreChild = Workflow.newChildWorkflowStub(
            ScoreWorkflow.class, childOptions("score"));

    @Override
    public String run(final Input input) {
        final LocalDate date = resolveTargetDate(input.date());
        LOG.info("ingest start  date={} poll={} force={}",
                date, input.pollUntilReady(), input.forceReingest());

        if (!input.forceReingest() && recorder.isAlreadyIngested(date)) {
            LOG.info("date already ingested — skipping  date={}", date);
            recordSkipped(date, "skipped_already_ingested");
            return "skipped_already_ingested";
        }

        String runId = recorder.startRun(date);

        try {
            downloadChild.run(new DownloadWorkflow.Input(date, input.pollUntilReady()));
        } catch (ChildWorkflowFailure cwf) {
            if (isCause(cwf, NotATradingDay.class.getName())) {
                LOG.info("not a trading day  date={}", date);
                recorder.completeRun(runId, "skipped_no_data", null, null, "{}");
                return "skipped_no_data";
            }
            throw cwf;
        }

        Promise<Long> parseP = Async.function(
                parseChild::run, new ParseWorkflow.Input(date, input.forceReingest()));
        Promise<RecordValidationActivity.Result> validateP = Async.function(
                validateChild::run, date);

        Long messageCount = null;
        String parseError = null;
        try {
            messageCount = parseP.get();
        } catch (ChildWorkflowFailure | ActivityFailure e) {
            parseError = causeMessage(e);
            LOG.error("parse failed  date={} err={}", date, parseError);
        }

        RecordValidationActivity.Result validation = null;
        String validateError = null;
        try {
            validation = validateP.get();
        } catch (ChildWorkflowFailure | ActivityFailure e) {
            validateError = causeMessage(e);
            LOG.error("validate failed  date={} err={}", date, validateError);
        }

        String finalStatus = computeFinalStatus(parseError, validation, validateError);
        String validatorStatus = (validation != null) ? validation.status() : "error";
        String notes = buildNotes(parseError, validation, validateError);
        recorder.completeRun(runId, finalStatus, messageCount, validatorStatus, notes);

        // Score only when parse succeeded; unverified data is still scoreable.
        if (parseError == null) {
            try {
                scoreChild.run(date);
            } catch (ChildWorkflowFailure cwf) {
                LOG.warn("score failed (returning status anyway)  date={} err={}",
                        date, cwf.getMessage());
            }
        } else {
            LOG.info("skipping score — parse failed  date={}", date);
        }

        LOG.info("ingest end  date={} status={}", date, finalStatus);
        return finalStatus;
    }

    private LocalDate resolveTargetDate(final LocalDate input) {
        if (!PLACEHOLDER_DATE.equals(input)) return input;
        ZonedDateTime nowET = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(Workflow.currentTimeMillis()),
                ZoneId.of("America/New_York"));
        LocalDate resolved = nowET.toLocalDate().minusDays(1);
        LOG.info("placeholder resolved to date={}", resolved);
        return resolved;
    }

    private void recordSkipped(final LocalDate date, final String status) {
        String runId = recorder.startRun(date);
        recorder.completeRun(runId, status, null, null, "{}");
    }

    private String computeFinalStatus(final String parseError,
                                      final RecordValidationActivity.Result validation,
                                      final String validateError) {
        if (parseError != null) return "parse_failed";
        if (validateError != null) return "validation_failed_data_ok";
        if (validation == null) return "validation_failed_data_ok";
        return switch (validation.status()) {
            case "passed" -> "ok";
            case "below_threshold" -> "unverified";
            default -> "unverified";
        };
    }

    private String buildNotes(final String parseError,
                              final RecordValidationActivity.Result validation,
                              final String validateError) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        if (parseError != null) {
            sb.append("\"parse_error\":").append(jsonString(parseError));
            first = false;
        }
        if (validateError != null) {
            if (!first) sb.append(",");
            sb.append("\"validate_error\":").append(jsonString(validateError));
            first = false;
        }
        if (validation != null && validation.notesJson() != null && !validation.notesJson().isBlank()) {
            if (!first) sb.append(",");
            sb.append("\"validation_notes\":").append(validation.notesJson());
        }
        sb.append("}");
        return sb.toString();
    }

    private static String jsonString(final String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static RetryOptions transientRetry(final int maxAttempts) {
        return RetryOptions.newBuilder()
                .setInitialInterval(Duration.ofSeconds(30))
                .setMaximumInterval(Duration.ofMinutes(2))
                .setBackoffCoefficient(2.0)
                .setMaximumAttempts(maxAttempts)
                .setDoNotRetry(NotATradingDay.class.getName())
                .build();
    }

    private static boolean isCause(final Throwable t, final String type) {
        Throwable cursor = t;
        while (cursor != null) {
            if (cursor instanceof ApplicationFailure appF && type.equals(appF.getType())) return true;
            cursor = cursor.getCause();
        }
        return false;
    }

    private static String causeMessage(final Throwable af) {
        Throwable t = af;
        Throwable last = af;
        while (t != null) { last = t; t = t.getCause(); }
        return last.getMessage();
    }
}
