package com.longexposure.temporal.workflows;

import com.longexposure.temporal.activities.CompressChunksActivity;
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
 * Top-level orchestrator. Reads as an outline of phases; the heavy
 * lifting lives in child workflows, each independently invokable
 * ad-hoc.
 *
 * <p>Composition principle: every <em>phase</em> of the pipeline is a
 * child workflow (Download, Parse, Validate, Score, Narrate, Interpret,
 * SynthesizeDay, Cleanup). The orchestrator itself only retains direct
 * activity calls for cross-cutting metadata that threads through the run —
 * {@link PipelineRunRecorderActivity}'s idempotency check + start/complete
 * bookkeeping — and the post-pipeline {@link CompressChunksActivity} that
 * compresses the day's chunks in-place so disk stays ~13 GB/day rather
 * than ~230 GB/day uncompressed. Everything else dispatches to child
 * workflows so the cron path and the ad-hoc developer-invoked path
 * share the same code.
 *
 * <p>Concurrency: Parse and Validate run in parallel (validators read
 * raw files from disk, not the DB, so they don't depend on parse).
 * Score, Narrate, Interpret, and SynthesizeDay are strictly sequential —
 * each consumes the previous stage's output and the three LLM-bound
 * stages compete for the same 2-concurrent {@code llama-large.joi}
 * slots, so even within one DailyPipelineWorkflow they cannot overlap.
 * Across days, this same constraint means running multiple
 * DailyPipelineWorkflow instances in parallel would oversubscribe the
 * LLM bottleneck — see {@code docs/operations.md} for the operational
 * rule "never run two LLM-bearing workflows concurrently."
 *
 * <p>Status semantics: see {@link #computeFinalStatus} for the truth
 * table mapping parse/validate outcomes to the final status string.
 */
public final class DailyPipelineWorkflowImpl implements DailyPipelineWorkflow {

    private static final Logger LOG = Workflow.getLogger(DailyPipelineWorkflowImpl.class);

    /**
     * Completed weeks of heavy wire/derived data to retain (the current
     * week is always kept on top of these). Week-aligned, minimum-2-full-
     * weeks policy — see {@link com.longexposure.temporal.activities.RetentionSweepActivity}.
     * Narratives / interpretations / daily_synthesis are kept indefinitely.
     */
    private static final int RETENTION_WEEKS = 2;

    /** Placeholder date used by the cron schedule — workflow resolves to yesterday-ET at start. */
    private static final LocalDate PLACEHOLDER_DATE = LocalDate.of(1970, 1, 1);

    // ─── Cross-cutting metadata activity ─────────────────────────────────────

    private final PipelineRunRecorderActivity recorder = Workflow.newActivityStub(
            PipelineRunRecorderActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(transientRetry(3))
                    .build());

    /**
     * Compress newly-ingested chunks at the end of each pipeline run.
     * Sized generously: empirically a full day's worth of chunks
     * (39 chunks, including the 75 GB order_lifecycle) takes ~26 min
     * to compress; 60 min start-to-close covers tail latency. Heartbeat
     * timeout 5 min — the longest single chunk (order_lifecycle) takes
     * ~7 min, but the implementation heartbeats between each
     * compress_chunk call.
     */
    private final CompressChunksActivity compressor = Workflow.newActivityStub(
            CompressChunksActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(60))
                    .setHeartbeatTimeout(Duration.ofMinutes(10))
                    .setRetryOptions(transientRetry(2))
                    .build());

    // ─── Child workflow stubs ────────────────────────────────────────────────

    private static ChildWorkflowOptions childOptions() {
        return ChildWorkflowOptions.newBuilder()
                .setTaskQueue(DailyPipelineWorkflow.TASK_QUEUE)
                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_TERMINATE)
                .build();
    }

    private final DownloadWorkflow downloadChild = Workflow.newChildWorkflowStub(
            DownloadWorkflow.class, childOptions());

    private final ParseWorkflow parseChild = Workflow.newChildWorkflowStub(
            ParseWorkflow.class, childOptions());

    private final ValidateWorkflow validateChild = Workflow.newChildWorkflowStub(
            ValidateWorkflow.class, childOptions());

    private final ScoreWorkflow scoreChild = Workflow.newChildWorkflowStub(
            ScoreWorkflow.class, childOptions());

    private final NarrateWorkflow narrateChild = Workflow.newChildWorkflowStub(
            NarrateWorkflow.class, childOptions());

    private final InterpretWorkflow interpretChild = Workflow.newChildWorkflowStub(
            InterpretWorkflow.class, childOptions());

    private final SynthesizeDayWorkflow synthesizeChild = Workflow.newChildWorkflowStub(
            SynthesizeDayWorkflow.class, childOptions());

    private final CleanupWorkflow cleanupChild = Workflow.newChildWorkflowStub(
            CleanupWorkflow.class, childOptions());

    // ─── run() ───────────────────────────────────────────────────────────────

    @Override
    public String run(final DailyPipelineWorkflowInput input) {
        final LocalDate date = resolveTargetDate(input);
        LOG.info("workflow start  date={} poll={} force={} sweep={}",
                date, input.pollUntilReady(), input.forceReingest(), input.runRetentionSweep());

        // Idempotency short-circuit.
        if (!input.forceReingest() && recorder.isAlreadyIngested(date)) {
            LOG.info("date already ingested — skipping  date={}", date);
            recordSkipped(date, "skipped_already_ingested");
            return "skipped_already_ingested";
        }

        String runId = recorder.startRun(date);

        // Phase 1: Download (3 feeds, in parallel inside the child workflow).
        // NotATradingDay short-circuits cleanly.
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

        // Phase 2: Parse and Validate concurrently. Parse writes to DB;
        // validate reads the raw .pcap.gz files. They don't depend on
        // each other so both can run in parallel.
        Promise<Long> parseP = Async.function(
                parseChild::run,
                new ParseWorkflow.Input(date, input.forceReingest()));

        Promise<RecordValidationActivity.Result> validateP = Async.function(
                validateChild::run, date);

        Long messageCount = null;
        String parseError = null;
        try {
            messageCount = parseP.get();
        } catch (ChildWorkflowFailure cwf) {
            parseError = cwf.getMessage();
            LOG.error("parse failed  date={} err={}", date, parseError);
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
            LOG.error("validate failed  date={} err={}", date, validateError);
        } catch (ActivityFailure af) {
            validateError = causeMessage(af);
            LOG.error("validate failed  date={} err={}", date, validateError);
        }

        String finalStatus = computeFinalStatus(parseError, validation, validateError);
        String validatorStatus = (validation != null) ? validation.status() : "error";
        String notes = buildNotes(parseError, validation, validateError);
        recorder.completeRun(runId, finalStatus, messageCount, validatorStatus, notes);

        // Phase 3: Score → Narrate → Interpret → Synthesize, only if
        // parse succeeded. Validation failure doesn't gate scoring —
        // unverified data is still queryable. Failures here log but
        // don't fail the workflow; downstream stages skip when their
        // upstream's output is missing, and each can be re-run via
        // its ad-hoc workflow.
        //
        // The four stages are strictly sequential by data dependency
        // and LLM concurrency:
        //   - Score writes scored_events that Narrate reads
        //   - Narrate writes narratives that Interpret + Synthesize read
        //   - Narrate, Interpret, and Synthesize all compete for the
        //     same 2-concurrent llama-large.joi slots
        if (parseError == null) {
            try {
                scoreChild.run(date);
            } catch (ChildWorkflowFailure cwf) {
                LOG.warn("score child failed (pipeline continues)  date={} err={}",
                        date, cwf.getMessage());
            }
            try {
                narrateChild.run(date);
            } catch (ChildWorkflowFailure cwf) {
                LOG.warn("narrate child failed (pipeline continues)  date={} err={}",
                        date, cwf.getMessage());
            }
            try {
                interpretChild.run(date);
            } catch (ChildWorkflowFailure cwf) {
                LOG.warn("interpret child failed (pipeline continues)  date={} err={}",
                        date, cwf.getMessage());
            }
            try {
                synthesizeChild.run(date);
            } catch (ChildWorkflowFailure cwf) {
                LOG.warn("synthesize child failed (pipeline continues)  date={} err={}",
                        date, cwf.getMessage());
            }
            // Compress the day's chunks now so disk stays lean.
            // Without this, ~230 GB of uncompressed data sits on disk
            // until the schema.sql compression policy's next daily run.
            try {
                long compressed = compressor.compress(date);
                LOG.info("compressed chunks  date={} count={}", date, compressed);
            } catch (ActivityFailure af) {
                LOG.warn("compress chunks failed (pipeline continues; policy will compress later)  date={} err={}",
                        date, af.getMessage());
            }
        } else {
            LOG.info("skipping score + narrate + interpret + synthesize + compress — parse failed  date={}", date);
        }

        // Phase 4: Cleanup + retention. Files deleted only on success;
        // retention sweep fires whenever cron mode is on regardless of
        // outcome (old chunks should age out either way).
        boolean deleteFiles = input.runRetentionSweep()
                && parseError == null
                && validation != null
                && "passed".equals(validation.status());

        cleanupChild.run(new CleanupWorkflow.Input(
                date, deleteFiles, input.runRetentionSweep(), RETENTION_WEEKS));

        LOG.info("workflow end  date={} status={}", date, finalStatus);
        return finalStatus;
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private LocalDate resolveTargetDate(final DailyPipelineWorkflowInput input) {
        if (!PLACEHOLDER_DATE.equals(input.targetDate())) return input.targetDate();
        // Scheduled run — resolve trading date as yesterday in ET.
        ZonedDateTime nowET = ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(Workflow.currentTimeMillis()),
                ZoneId.of("America/New_York"));
        LocalDate resolved = nowET.toLocalDate().minusDays(1);
        LOG.info("scheduled run — resolved placeholder to date={}", resolved);
        return resolved;
    }

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
