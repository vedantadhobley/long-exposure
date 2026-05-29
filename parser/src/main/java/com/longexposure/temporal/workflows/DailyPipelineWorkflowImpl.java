package com.longexposure.temporal.workflows;

import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.LocalDate;

/**
 * Top-level per-day orchestrator. Composes {@link IngestDayWorkflow},
 * {@link LlmDayWorkflow}, and {@link FinalizeDayWorkflow} in sequence.
 *
 * <p>Stage 4 (2026-05-29) — was previously a 400-line monolith with inline
 * Download/Parse/Validate/Score/Narrate/Interpret/Synthesize/Aggregate/
 * Compress/Cleanup wiring. Refactored into three composable sub-workflows
 * so {@link PipelineWorkflow} can sliding-window them for Phase A/B
 * overlap (Stage 5).
 *
 * <p>Status returned matches the legacy contract exactly — callers of
 * DailyPipelineWorkflow still see the same status strings
 * ({@code ok}, {@code unverified}, {@code parse_failed},
 * {@code skipped_already_ingested}, {@code skipped_no_data}).
 */
public final class DailyPipelineWorkflowImpl implements DailyPipelineWorkflow {

    private static final Logger LOG = Workflow.getLogger(DailyPipelineWorkflowImpl.class);
    private static final LocalDate PLACEHOLDER_DATE = LocalDate.of(1970, 1, 1);

    private static ChildWorkflowOptions childOptions(final String phase) {
        return ChildWorkflowOptions.newBuilder()
                .setWorkflowId(Workflow.getInfo().getWorkflowId() + "-" + phase)
                .setTaskQueue(DailyPipelineWorkflow.TASK_QUEUE)
                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_TERMINATE)
                .build();
    }

    private final IngestDayWorkflow ingestChild = Workflow.newChildWorkflowStub(
            IngestDayWorkflow.class, childOptions("ingest"));
    private final LlmDayWorkflow llmChild = Workflow.newChildWorkflowStub(
            LlmDayWorkflow.class, childOptions("llm"));
    private final FinalizeDayWorkflow finalizeChild = Workflow.newChildWorkflowStub(
            FinalizeDayWorkflow.class, childOptions("finalize"));
    private final AggregateWeekWorkflow aggregateWeekChild = Workflow.newChildWorkflowStub(
            AggregateWeekWorkflow.class, childOptions("aggregate-week"));
    private final AggregateQuarterWorkflow aggregateQuarterChild = Workflow.newChildWorkflowStub(
            AggregateQuarterWorkflow.class, childOptions("aggregate-quarter"));
    private final AggregateYearWorkflow aggregateYearChild = Workflow.newChildWorkflowStub(
            AggregateYearWorkflow.class, childOptions("aggregate-year"));

    @Override
    public String run(final DailyPipelineWorkflowInput input) {
        LocalDate date = input.targetDate();
        LOG.info("daily start  date={} poll={} force={} sweep={}",
                date, input.pollUntilReady(), input.forceReingest(), input.runRetentionSweep());

        // Ingest: idempotency check + Download + Parse + Validate + Score.
        // Returns the status string we propagate back to the caller.
        String status = ingestChild.run(new IngestDayWorkflow.Input(
                date, input.pollUntilReady(), input.forceReingest()));

        // Short-circuit statuses — no LLM, no finalize.
        if ("skipped_already_ingested".equals(status) || "skipped_no_data".equals(status)) {
            LOG.info("daily end (short-circuit)  date={} status={}", date, status);
            return status;
        }

        // Resolve the placeholder if cron-fired; IngestDay logged the resolved
        // date but we need it here for downstream calls.
        LocalDate resolvedDate = PLACEHOLDER_DATE.equals(date)
                ? java.time.ZonedDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(Workflow.currentTimeMillis()),
                        java.time.ZoneId.of("America/New_York"))
                    .toLocalDate().minusDays(1)
                : date;

        // LLM chain — only when ingest succeeded (parse OK; validation
        // failures still allow scoring + narration on unverified data).
        if (!"parse_failed".equals(status)) {
            try {
                llmChild.run(resolvedDate);
            } catch (ChildWorkflowFailure cwf) {
                LOG.warn("llm child failed (pipeline continues)  date={} err={}",
                        resolvedDate, cwf.getMessage());
            }

            // Per-day rollup cascade. Content-addressed; each tier no-ops
            // cheaply when its inputs are unchanged. Quarterly + yearly are
            // gated dormant until their MIN_* thresholds are met.
            for (var step : new Object[][]{
                    {"aggregate-week",    (Runnable) () -> aggregateWeekChild.run(resolvedDate)},
                    {"aggregate-quarter", (Runnable) () -> aggregateQuarterChild.run(resolvedDate)},
                    {"aggregate-year",    (Runnable) () -> aggregateYearChild.run(resolvedDate)}}) {
                try {
                    ((Runnable) step[1]).run();
                } catch (ChildWorkflowFailure cwf) {
                    LOG.warn("{} failed (pipeline continues)  date={} err={}",
                            step[0], resolvedDate, cwf.getMessage());
                }
            }
        } else {
            LOG.info("skipping llm + aggregate — parse failed  date={}", resolvedDate);
        }

        // Finalize: compress chunks + cleanup. Always runs (cleanup
        // gates internally on deleteFiles).
        boolean deleteFiles = input.runRetentionSweep() && "ok".equals(status);
        try {
            finalizeChild.run(new FinalizeDayWorkflow.Input(
                    resolvedDate, deleteFiles, input.runRetentionSweep()));
        } catch (ChildWorkflowFailure cwf) {
            LOG.warn("finalize child failed  date={} err={}",
                    resolvedDate, cwf.getMessage());
        }

        LOG.info("daily end  date={} status={}", resolvedDate, status);
        return status;
    }
}
