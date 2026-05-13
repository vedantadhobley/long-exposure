package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.CleanupFilesActivity;
import com.longexposure.temporal.activities.DeepTopsValidatorActivity;
import com.longexposure.temporal.activities.DownloadFileActivity;
import com.longexposure.temporal.activities.DplsDeepValidatorActivity;
import com.longexposure.temporal.activities.DplsTopsValidatorActivity;
import com.longexposure.temporal.activities.Feed;
import com.longexposure.temporal.activities.FilesNotReady;
import com.longexposure.temporal.activities.NotATradingDay;
import com.longexposure.temporal.activities.ParseAndWriteDplsActivity;
import com.longexposure.temporal.activities.PipelineRunRecorderActivity;
import com.longexposure.temporal.activities.RecordValidationActivity;
import com.longexposure.temporal.activities.ResolveUrlActivity;
import com.longexposure.temporal.activities.RetentionSweepActivity;
import com.longexposure.temporal.activities.ScoreEventsActivity;
import com.longexposure.temporal.activities.ValidationLegResult;
import java.util.UUID;
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

    private final DplsDeepValidatorActivity dplsDeepLeg = Workflow.newActivityStub(
            DplsDeepValidatorActivity.class, legOptions());
    private final DplsTopsValidatorActivity dplsTopsLeg = Workflow.newActivityStub(
            DplsTopsValidatorActivity.class, legOptions());
    private final DeepTopsValidatorActivity deepTopsLeg = Workflow.newActivityStub(
            DeepTopsValidatorActivity.class, legOptions());

    private final RecordValidationActivity recordValidation = Workflow.newActivityStub(
            RecordValidationActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(2))
                    .setRetryOptions(transientRetry(3))
                    .build());

    private final ScoreEventsActivity scoreEvents = Workflow.newActivityStub(
            ScoreEventsActivity.class,
            ActivityOptions.newBuilder()
                    // Scoring reads from hypertables + writes thousands of
                    // rows to scored_events. Should be quick for v1 (just
                    // halts). Generous timeout so adding scorers doesn't
                    // immediately require re-tuning.
                    .setStartToCloseTimeout(Duration.ofMinutes(30))
                    .setHeartbeatTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(transientRetry(2))
                    .build());

    private static ActivityOptions legOptions() {
        // Each leg runs in its own activity; heartbeat timeout only has to
        // cover that one leg's inner loop (8-15 min observed). Validators
        // don't heartbeat internally, so we set start-to-close generously
        // and skip heartbeat config — Temporal infers liveness from the
        // activity's task-queue poll.
        return ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(30))
                .setRetryOptions(transientRetry(2))
                .build();
    }

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

        // Three validator legs run concurrently as soon as the right two
        // downloads finish (each leg only needs its own pair of files).
        Promise<ValidationLegResult> dplsDeepP = Promise.allOf(dplsDownP, deepDownP)
                .thenCompose(v -> Async.function(dplsDeepLeg::validate, dplsPath, deepPath));
        Promise<ValidationLegResult> dplsTopsP = Promise.allOf(dplsDownP, topsDownP)
                .thenCompose(v -> Async.function(dplsTopsLeg::validate, dplsPath, topsPath));
        Promise<ValidationLegResult> deepTopsP = Promise.allOf(deepDownP, topsDownP)
                .thenCompose(v -> Async.function(deepTopsLeg::validate, deepPath, topsPath));

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

        ValidationLegResult dplsDeep = tryGet(dplsDeepP, "DPLS↔DEEP");
        ValidationLegResult dplsTops = tryGet(dplsTopsP, "DPLS→TOPS");
        ValidationLegResult deepTops = tryGet(deepTopsP, "DEEP→TOPS");

        // Persist validation_runs row.
        RecordValidationActivity.Result validation;
        String validateError = null;
        try {
            validation = recordValidation.record(date, dplsDeep, dplsTops, deepTops);
        } catch (ActivityFailure af) {
            validateError = causeMessage(af);
            LOG.error("recordValidation failed  date={} err={}", date, validateError);
            validation = null;
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
        // separately. UUID translates to NULL on parse failure path.
        if (parseError == null) {
            UUID runUuid = null;
            try { runUuid = UUID.fromString(runId); } catch (IllegalArgumentException ignored) {}
            try {
                long scored = scoreEvents.scoreEvents(date, runUuid);
                LOG.info("scoring done  date={} scored_events_written={}", date, scored);
            } catch (ActivityFailure af) {
                LOG.warn("scoring failed (workflow continues)  date={} err={}", date, causeMessage(af));
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

    /** Resolve a leg promise; log + return null instead of throwing if the activity failed. */
    private static ValidationLegResult tryGet(final Promise<ValidationLegResult> p, final String legName) {
        try {
            return p.get();
        } catch (ActivityFailure af) {
            Workflow.getLogger(DailyPipelineWorkflowImpl.class).error(
                    "validator leg failed  leg={} err={}", legName, causeMessage(af));
            return null;
        }
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
