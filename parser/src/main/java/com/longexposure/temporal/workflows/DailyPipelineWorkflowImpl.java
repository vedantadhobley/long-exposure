package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.CleanupFilesActivity;
import com.longexposure.temporal.activities.DownloadFileActivity;
import com.longexposure.temporal.activities.Feed;
import com.longexposure.temporal.activities.FilesNotReady;
import com.longexposure.temporal.activities.NotATradingDay;
import com.longexposure.temporal.activities.ParseAndWriteDplsActivity;
import com.longexposure.temporal.activities.PipelineRunRecorderActivity;
import com.longexposure.temporal.activities.RecordValidationActivity;
import com.longexposure.temporal.activities.ResolveUrlActivity;
import com.longexposure.temporal.activities.RetentionSweepActivity;
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
import java.util.List;

/**
 * Implementation of {@link DailyPipelineWorkflow}. See the workflow
 * interface javadoc + {@code docs/temporal-design.md} for the full design.
 *
 * <p>Dependency graph (see design doc):
 * <pre>
 *           ┌─→ ParseAndWriteDpls ─┐
 * DPLS ────┤                       │
 *           └─→                    │
 *                                  ├─→ Cleanup → [if cron] RetentionSweep
 *           ┌─→                    │
 * DEEP ────┼─→ ValidateTriangle ──┘
 *           │
 * TOPS ────┘
 * </pre>
 */
public final class DailyPipelineWorkflowImpl implements DailyPipelineWorkflow {

    private static final Logger LOG = Workflow.getLogger(DailyPipelineWorkflowImpl.class);

    /** Where downloaded files land inside the worker container. */
    private static final String RAW_DIR = "/storage/raw";

    /** Days to retain in Postgres. */
    private static final int RETENTION_DAYS = 30;

    // ─── Activity stubs ──────────────────────────────────────────────────────

    private final PipelineRunRecorderActivity recorder = Workflow.newActivityStub(
            PipelineRunRecorderActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(transientRetry(3))
                    .build());

    /**
     * URL resolution. Retry policy is set per-invocation because the
     * polling vs non-polling behavior differs between cron and ad-hoc:
     * see {@link #resolveStub(boolean)}.
     */
    private ResolveUrlActivity resolveStub(final boolean pollUntilReady) {
        RetryOptions retry = pollUntilReady
                ? RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofMinutes(15))
                        .setMaximumInterval(Duration.ofMinutes(15))
                        .setBackoffCoefficient(1.0)
                        .setMaximumAttempts(0)   // unlimited; bounded by schedule-to-close
                        .setDoNotRetry(NotATradingDay.class.getName())
                        .build()
                : RetryOptions.newBuilder()
                        .setInitialInterval(Duration.ofSeconds(15))
                        .setMaximumAttempts(3)
                        .setDoNotRetry(NotATradingDay.class.getName(), FilesNotReady.class.getName())
                        .build();
        Duration schedToClose = pollUntilReady ? Duration.ofHours(3) : Duration.ofMinutes(1);
        return Workflow.newActivityStub(
                ResolveUrlActivity.class,
                ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(30))
                        .setScheduleToCloseTimeout(schedToClose)
                        .setRetryOptions(retry)
                        .build());
    }

    private final DownloadFileActivity downloader = Workflow.newActivityStub(
            DownloadFileActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofHours(1))
                    .setHeartbeatTimeout(Duration.ofMinutes(2))
                    .setRetryOptions(transientRetry(5))
                    .build());

    private final ParseAndWriteDplsActivity parser = Workflow.newActivityStub(
            ParseAndWriteDplsActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofHours(2))
                    // 15 min is wide enough to cover the pre-clean DELETE of a
                    // full day's DPLS rows (~5 min for 364M rows observed) plus
                    // headroom; parse-loop heartbeats fire every 100K messages
                    // (sub-second cadence) so this only matters for the SQL stages.
                    .setHeartbeatTimeout(Duration.ofMinutes(15))
                    .setRetryOptions(transientRetry(3))
                    .build());

    // ─── Child workflow stubs ────────────────────────────────────────────────
    //
    // The validate / score / narrate phases are owned by their own child
    // workflows so the daily-cron path and the ad-hoc developer-invoked
    // path run the IDENTICAL code. Single source of truth per phase.
    //
    // Child workflow IDs are auto-generated by Temporal (unset in options),
    // which avoids collisions with ad-hoc invocations that use their own
    // explicit workflow IDs. ParentClosePolicy.TERMINATE means killing the
    // daily pipeline cleanly cascades to its in-flight phases.

    private static ChildWorkflowOptions childOptions() {
        return ChildWorkflowOptions.newBuilder()
                .setTaskQueue(DailyPipelineWorkflow.TASK_QUEUE)
                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_TERMINATE)
                .build();
    }

    private final ValidateWorkflow validateChild = Workflow.newChildWorkflowStub(
            ValidateWorkflow.class, childOptions());

    private final ScoreWorkflow scoreChild = Workflow.newChildWorkflowStub(
            ScoreWorkflow.class, childOptions());

    private final NarrateWorkflow narrateChild = Workflow.newChildWorkflowStub(
            NarrateWorkflow.class, childOptions());

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
                    .setRetryOptions(transientRetry(3))
                    .build());

    // ─── run() ───────────────────────────────────────────────────────────────

    /** Placeholder date used by the cron schedule — workflow resolves to yesterday-ET at start. */
    private static final LocalDate PLACEHOLDER_DATE = LocalDate.of(1970, 1, 1);

    @Override
    public String run(final DailyPipelineWorkflowInput input) {
        LocalDate resolved = input.targetDate();
        if (PLACEHOLDER_DATE.equals(resolved)) {
            // Scheduled run — resolve trading date as yesterday in ET.
            ZonedDateTime nowET = ZonedDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(Workflow.currentTimeMillis()),
                    ZoneId.of("America/New_York"));
            resolved = nowET.toLocalDate().minusDays(1);
            LOG.info("scheduled run — resolved placeholder to date={}", resolved);
        }
        final LocalDate date = resolved;
        LOG.info("workflow start  date={} poll={} force={} sweep={}",
                date, input.pollUntilReady(), input.forceReingest(), input.runRetentionSweep());

        // Pre-check: already ingested?
        if (!input.forceReingest() && recorder.isAlreadyIngested(date)) {
            LOG.info("date already ingested — skipping  date={}", date);
            recordSkipped(date, "skipped_already_ingested");
            return "skipped_already_ingested";
        }

        String runId = recorder.startRun(date);

        // Resolve all three URLs in parallel. NotATradingDay on any of
        // them short-circuits to skipped_no_data.
        ResolveUrlActivity resolve = resolveStub(input.pollUntilReady());
        Promise<String> dplsUrlP = Async.function(resolve::resolveUrl, date, Feed.DPLS);
        Promise<String> deepUrlP = Async.function(resolve::resolveUrl, date, Feed.DEEP);
        Promise<String> topsUrlP = Async.function(resolve::resolveUrl, date, Feed.TOPS);

        final String dplsUrl, deepUrl, topsUrl;
        try {
            dplsUrl = dplsUrlP.get();
            deepUrl = deepUrlP.get();
            topsUrl = topsUrlP.get();
        } catch (ActivityFailure af) {
            if (isCause(af, NotATradingDay.class.getName())) {
                LOG.info("not a trading day  date={}", date);
                recorder.completeRun(runId, "skipped_no_data", null, null, "{}");
                return "skipped_no_data";
            }
            throw af;
        }

        // Three concurrent downloads. Destination paths are deterministic.
        String dplsPath = pathFor(date, Feed.DPLS);
        String deepPath = pathFor(date, Feed.DEEP);
        String topsPath = pathFor(date, Feed.TOPS);

        Promise<String> dplsDownP = Async.function(downloader::downloadFile, dplsUrl, dplsPath);
        Promise<String> deepDownP = Async.function(downloader::downloadFile, deepUrl, deepPath);
        Promise<String> topsDownP = Async.function(downloader::downloadFile, topsUrl, topsPath);

        // Kick off parse as soon as DPLS download completes.
        Promise<Long> parseP = dplsDownP.thenCompose(p ->
                Async.function(parser::parseAndWrite, p, date, input.forceReingest()));

        // Kick off the validate child workflow as soon as ALL THREE
        // downloads finish. The child internally runs the 3 legs in
        // parallel and persists to validation_runs. Returns the full
        // Result (status + per-leg notes JSON).
        Promise<RecordValidationActivity.Result> validateP = Promise.allOf(
                dplsDownP, deepDownP, topsDownP)
                .thenCompose(v -> Async.function(validateChild::run, date));

        // Await both parse + validate. Each may fail independently — we
        // want both signals before deciding final status.
        Long messageCount = null;
        String parseError = null;
        try {
            messageCount = parseP.get();
        } catch (ActivityFailure af) {
            parseError = causeMessage(af);
            LOG.error("parse failed  date={} err={}", date, parseError);
        }

        RecordValidationActivity.Result validation = null;
        String validateError = null;
        try {
            validation = validateP.get();
        } catch (ChildWorkflowFailure cwf) {
            validateError = cwf.getMessage();
            LOG.error("validate child workflow failed  date={} err={}", date, validateError);
        } catch (ActivityFailure af) {
            // Activity failure that surfaced through the child workflow.
            validateError = causeMessage(af);
            LOG.error("validate child activity failed  date={} err={}", date, validateError);
        }

        // Decide final status.
        String finalStatus = computeFinalStatus(parseError, validation, validateError);
        String validatorStatus = (validation != null) ? validation.status() : "error";
        String notes = buildNotes(parseError, validation, validateError);

        recorder.completeRun(runId, finalStatus, messageCount, validatorStatus, notes);

        // Score: runs when parse succeeded, regardless of validation status.
        // Unverified data is still queryable and scoreable — downstream
        // narration will skip unverified dates per the design doc.
        // Failure here doesn't fail the workflow; scoring can be re-run
        // via the ScoreWorkflow standalone entry point.
        if (parseError == null) {
            try {
                long scored = scoreChild.run(date);
                LOG.info("scoring done  date={} scored_events_written={}", date, scored);

                // Narrate the selected events. Failure here logs but
                // doesn't fail the workflow: narration can be re-run via
                // NarrateWorkflow later without losing scored/selected data.
                try {
                    long narrated = narrateChild.run(date);
                    LOG.info("narration done  date={} narratives_written={}", date, narrated);
                } catch (ChildWorkflowFailure cwf) {
                    LOG.warn("narrate child workflow failed (pipeline continues)  date={} err={}",
                            date, cwf.getMessage());
                }
            } catch (ChildWorkflowFailure cwf) {
                LOG.warn("score child workflow failed (pipeline continues)  date={} err={}",
                        date, cwf.getMessage());
            }
        } else {
            LOG.info("skipping scoring — parse failed  date={}", date);
        }

        // Cleanup: only in cron mode AND when both parse + validate succeeded.
        // Ad-hoc runs ({@code runRetentionSweep=false}) preserve files so the
        // operator can rerun, debug, or compare without re-downloading.
        boolean cleanupEligible = input.runRetentionSweep()
                && parseError == null
                && validation != null
                && "passed".equals(validation.status());
        // (used below)
        if (cleanupEligible) {
            cleanup.cleanup(List.of(dplsPath, deepPath, topsPath));
        } else {
            LOG.info("skipping cleanup — files retained  date={} status={} cron_mode={}",
                    date, finalStatus, input.runRetentionSweep());
        }

        // Retention sweep (cron mode only). Failure here doesn't fail the workflow.
        if (input.runRetentionSweep()) {
            try {
                retention.sweep(date, RETENTION_DAYS);
            } catch (ActivityFailure af) {
                LOG.warn("retention sweep failed  err={}", causeMessage(af));
            }
        }

        LOG.info("workflow end  date={} status={}", date, finalStatus);
        return finalStatus;
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private void recordSkipped(final LocalDate date, final String status) {
        String runId = recorder.startRun(date);
        recorder.completeRun(runId, status, null, null, "{}");
    }

    /**
     * Compute the final workflow status from parse + validate outcomes.
     * <ul>
     *   <li>{@code ok} — both succeeded and validation passed
     *   <li>{@code unverified} — parse ok, validation below threshold
     *   <li>{@code parse_failed} — parse threw; validation may or may not have run
     *   <li>{@code validation_failed_data_ok} — parse ok, validation activity threw
     * </ul>
     */
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

    private static String pathFor(final LocalDate date, final Feed feed) {
        String version = switch (feed) {
            case TOPS -> "1.6";
            case DEEP -> "1.0";
            case DPLS -> "1.0";
        };
        return RAW_DIR + "/" + date.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "_IEXTP1_" + feed.name() + version + ".pcap.gz";
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

    private static boolean isCause(final ActivityFailure af, final String type) {
        Throwable t = af;
        while (t != null) {
            if (t instanceof ApplicationFailure appF && type.equals(appF.getType())) return true;
            t = t.getCause();
        }
        return false;
    }

    private static String causeMessage(final ActivityFailure af) {
        Throwable t = af.getCause();
        return (t != null && t.getMessage() != null) ? t.getMessage() : af.getMessage();
    }
}
