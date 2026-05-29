package com.longexposure.temporal.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.time.LocalDate;
import java.util.List;

/**
 * Unified entry point for all pipeline execution modes: cron-driven daily,
 * ad-hoc single-day, and multi-day backfill / rerun. Replaces the
 * {@code scripts/rerun-dataset-*.sh} shell scripts (orchestration logic
 * moves into Temporal where it belongs — durable, restartable, testable).
 *
 * <p><b>Coverage</b>:
 * <ul>
 *   <li>Cron:   {@code PipelineWorkflow.run(dates=[yesterday-placeholder], pollUntilReady=true)}
 *   <li>Ad-hoc: {@code PipelineWorkflow.run(dates=[2026-05-12])}
 *   <li>Rerun:  {@code PipelineWorkflow.run(dates=[12 dates], cascadeRollups=true)}
 * </ul>
 *
 * <p><b>Per-day work</b>: each date fires {@link DailyPipelineWorkflow} as a
 * child workflow. That child already handles the per-day chain (download →
 * parse → score → narrate → interpret → synthesize → compress + retention).
 * Content-addressed memoization at every stage makes already-done dates a
 * cheap no-op.
 *
 * <p><b>Rollup cascade</b>: when {@code cascadeRollups=true}, after the
 * per-day work completes, the workflow fires {@link AggregateWeekWorkflow}
 * for each touched week, {@link AggregateQuarterWorkflow} for each touched
 * quarter, and {@link AggregateYearWorkflow} for each touched year. Each
 * rollup is content-addressed (no-ops cheaply when inputs unchanged).
 *
 * <p><b>Parallelization</b> (v1, this commit): per-day runs sequentially.
 * The Phase A (download/parse/score) vs Phase B (LLM stages) overlap that
 * would save ~3-4 hours on a 12-day backfill is a planned v2 optimization;
 * it requires either restructuring DailyPipelineWorkflow to expose its
 * phases as separately-callable workflows, or having PipelineWorkflow call
 * the individual phase workflows directly. v1 keeps the call clean and
 * leaves the optimization for a follow-up.
 */
@WorkflowInterface
public interface PipelineWorkflow {

    @WorkflowMethod
    PipelineResult run(PipelineInput input);

    /**
     * Execution mode for the per-day chain.
     */
    enum Mode {
        /** Default — run the full per-day pipeline via {@link DailyPipelineWorkflow}. */
        FULL_PIPELINE,
        /** Skip parse/score/materialize; run only Narrate → Interpret → Synthesize. */
        LLM_CHAIN
    }

    /**
     * @param dates             list of trading dates to process (size 1 to N).
     *                          For cron, pass a single date — the placeholder
     *                          {@link DailyPipelineWorkflow#PLACEHOLDER_DATE}
     *                          works the same way as the existing cron path
     *                          (resolves to "yesterday in ET" at fire time).
     * @param pollUntilReady    cron-mode HIST-availability poll: retry the URL
     *                          resolve when the file isn't published yet.
     *                          {@code FULL_PIPELINE} only.
     * @param forceReingest     bypass {@code pipeline_runs.status='ok'} check
     *                          so a previously-completed date re-runs.
     *                          {@code FULL_PIPELINE} only.
     * @param runRetentionSweep run the week-aligned 2-week retention sweep at
     *                          the end of each day's pipeline. Typically true
     *                          for cron, false for ad-hoc / rerun.
     *                          {@code FULL_PIPELINE} only.
     * @param cascadeRollups    after all per-day work, fire the rollup
     *                          workflows (week / quarter / year) for every
     *                          period touched by the input dates. Idempotent
     *                          via each rollup's content_hash.
     * @param mode              {@code FULL_PIPELINE} runs {@link DailyPipelineWorkflow}
     *                          per date. {@code LLM_CHAIN} runs
     *                          {@link NarrateWorkflow} →
     *                          {@link InterpretWorkflow} →
     *                          {@link SynthesizeDayWorkflow} per date directly,
     *                          skipping the parse/score idempotency guard. Use
     *                          {@code LLM_CHAIN} for re-runs after a
     *                          PROMPT_VERSION bump or to backfill INTERPRET
     *                          coverage. Null = {@code FULL_PIPELINE}.
     */
    record PipelineInput(
            List<LocalDate> dates,
            boolean         pollUntilReady,
            boolean         forceReingest,
            boolean         runRetentionSweep,
            boolean         cascadeRollups,
            Mode            mode) {

        /** Back-compat constructor — defaults mode to FULL_PIPELINE. */
        public PipelineInput(List<LocalDate> dates,
                              boolean pollUntilReady,
                              boolean forceReingest,
                              boolean runRetentionSweep,
                              boolean cascadeRollups) {
            this(dates, pollUntilReady, forceReingest, runRetentionSweep, cascadeRollups, Mode.FULL_PIPELINE);
        }
    }

    /**
     * @param daysProcessed     number of dates pushed through the per-day chain
     * @param weeklyRollups     number of AggregateWeek invocations during cascade
     * @param quarterlyRollups  number of AggregateQuarter invocations during cascade
     * @param yearlyRollups     number of AggregateYear invocations during cascade
     * @param elapsedMs         total wall-clock time of the workflow
     */
    record PipelineResult(
            int  daysProcessed,
            int  weeklyRollups,
            int  quarterlyRollups,
            int  yearlyRollups,
            long elapsedMs) {}
}
