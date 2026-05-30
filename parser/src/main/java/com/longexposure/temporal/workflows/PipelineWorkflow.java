package com.longexposure.temporal.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

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
        LLM_CHAIN,
        /**
         * Re-score then run the LLM chain per day. Use when a scorer change
         * or breakdown enrichment requires fresh {@code scored_events} +
         * {@code selected_events} but the parsed wire data is already loaded.
         * Replaces the {@code scripts/rescore-rerun-*.sh} bash drivers.
         */
        SCORE_AND_LLM
    }

    /**
     * Per-day phase. Used as an optional fine-grained filter on top of
     * {@link Mode}: pass {@code stages=null} (default) to let Mode pick the
     * phases; pass a non-empty set to run ONLY those phases.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code stages=[INGEST]} — parse + score, no LLM, no cleanup
     *   <li>{@code stages=[FINALIZE]} — compress + cleanup only
     *   <li>{@code stages=[LLM, FINALIZE]} — LLM chain + post-LLM cleanup
     *       (assumes ingest already happened)
     * </ul>
     *
     * <p>{@link Mode#LLM_CHAIN} and {@link Mode#SCORE_AND_LLM} are fixed-
     * shape presets — they ignore {@code stages}.
     */
    enum Stage {
        /** {@link IngestDayWorkflow}: Download + Parse + Validate + Score per day. */
        INGEST,
        /** {@link LlmDayWorkflow}: Narrate + Interpret + Synthesize per day. */
        LLM,
        /** {@link FinalizeDayWorkflow}: Compress chunks + Cleanup files per day. */
        FINALIZE
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
    /**
     * Inclusive date range. {@code from} ≤ {@code to}, both required.
     * Expanded to weekdays only (US market trading calendar). Holidays
     * passed through — the per-day workflow short-circuits on {@code
     * NotATradingDay} from {@link com.longexposure.temporal.activities.ResolveUrlActivity}.
     */
    record DateRange(LocalDate from, LocalDate to) {}

    /**
     * Canonical input.
     *
     * <p><b>Target shapes</b> (any combination; all unioned into one scope):
     * <ul>
     *   <li>{@code dates} — individual trading days.
     *   <li>{@code dateRanges} — inclusive {@code [from, to]} day ranges (weekday-filtered).
     *   <li>{@code weekAnchors} — any date in a week; normalized to that week's Monday.
     *       Use this to re-aggregate weeks without re-running their days.
     *   <li>{@code quarterAnchors} — any date in a quarter; normalized to {@code Jan/Apr/Jul/Oct 1}.
     *       Use to re-aggregate quarters directly.
     *   <li>{@code yearAnchors} — any date in a year; normalized to {@code Jan 1}.
     *   <li>{@code cascadeFrom} — horizontal ripple anchor. Expands to every weekly,
     *       quarterly, and yearly period from this date forward through today (ET).
     *       Use when a historical change ripples through subsequent period prior-windows
     *       (e.g., re-synthesizing day 05-13 invalidates every weekly rollup whose
     *       prior-window includes week-of-05-11).
     * </ul>
     *
     * <p><b>Cascade ordering</b>: within one workflow call, period work runs strictly
     * chronological at each tier (weeks left-to-right, then quarters, then years).
     * Each period waits on its day-level inputs (if any are in scope) AND on the
     * previous-period rollup at the same tier (so prior-window reads see fresh
     * output). Same-tier periods serialize on the joi LLM mutex anyway, so this
     * doesn't lose wall-clock — it just guarantees causal ordering across the
     * prior-window read pattern.
     */
    record PipelineInput(
            List<LocalDate> dates,
            List<DateRange> dateRanges,
            List<LocalDate> weekAnchors,
            List<LocalDate> quarterAnchors,
            List<LocalDate> yearAnchors,
            LocalDate       cascadeFrom,
            boolean         pollUntilReady,
            boolean         forceReingest,
            boolean         runRetentionSweep,
            boolean         cascadeRollups,
            Mode            mode,
            Set<Stage>      stages) {

        /** Back-compat constructor — defaults mode to FULL_PIPELINE, no ranges, no anchors, no stage filter. */
        public PipelineInput(List<LocalDate> dates,
                              boolean pollUntilReady,
                              boolean forceReingest,
                              boolean runRetentionSweep,
                              boolean cascadeRollups) {
            this(dates, null, null, null, null, null,
                 pollUntilReady, forceReingest, runRetentionSweep, cascadeRollups,
                 Mode.FULL_PIPELINE, null);
        }

        /** Back-compat constructor with mode. */
        public PipelineInput(List<LocalDate> dates,
                              boolean pollUntilReady,
                              boolean forceReingest,
                              boolean runRetentionSweep,
                              boolean cascadeRollups,
                              Mode mode) {
            this(dates, null, null, null, null, null,
                 pollUntilReady, forceReingest, runRetentionSweep, cascadeRollups,
                 mode, null);
        }

        /** Back-compat constructor with date ranges + mode. */
        public PipelineInput(List<LocalDate> dates,
                              List<DateRange> dateRanges,
                              boolean pollUntilReady,
                              boolean forceReingest,
                              boolean runRetentionSweep,
                              boolean cascadeRollups,
                              Mode mode) {
            this(dates, dateRanges, null, null, null, null,
                 pollUntilReady, forceReingest, runRetentionSweep, cascadeRollups,
                 mode, null);
        }

        /** Back-compat constructor with date ranges + mode + stages (pre-anchors shape). */
        public PipelineInput(List<LocalDate> dates,
                              List<DateRange> dateRanges,
                              boolean pollUntilReady,
                              boolean forceReingest,
                              boolean runRetentionSweep,
                              boolean cascadeRollups,
                              Mode mode,
                              Set<Stage> stages) {
            this(dates, dateRanges, null, null, null, null,
                 pollUntilReady, forceReingest, runRetentionSweep, cascadeRollups,
                 mode, stages);
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
