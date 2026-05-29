package com.longexposure.temporal.workflows;

import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static com.longexposure.temporal.workflows.PipelineWorkflow.Mode;

public final class PipelineWorkflowImpl implements PipelineWorkflow {

    private static final Logger LOG = Workflow.getLogger(PipelineWorkflowImpl.class);

    @Override
    public PipelineResult run(final PipelineInput input) {
        long t0 = System.currentTimeMillis();
        Mode mode = (input.mode() == null) ? Mode.FULL_PIPELINE : input.mode();

        // Stage 1: expand dates + dateRanges into an ordered, deduplicated,
        // weekday-only list. Caller can pass any combination: individual
        // dates, ranges, multiple ranges, or all three (union).
        java.util.List<LocalDate> effectiveDates = expandDates(
                input.dates(), input.dateRanges());
        if (effectiveDates.isEmpty()) {
            LOG.warn("pipeline start  no effective dates  dates={} dateRanges={}",
                    input.dates(), input.dateRanges());
            return new PipelineResult(0, 0, 0, 0, System.currentTimeMillis() - t0);
        }

        LOG.info("pipeline start  mode={} dates_count={} (from dates={} ranges={}) cascade={} forceReingest={} retentionSweep={}",
                mode, effectiveDates.size(),
                input.dates() == null ? 0 : input.dates().size(),
                input.dateRanges() == null ? 0 : input.dateRanges().size(),
                input.cascadeRollups(), input.forceReingest(), input.runRetentionSweep());

        // Per-day work — dispatched by mode + size.
        //   LLM_CHAIN: fires LlmDayWorkflow per date sequentially. joi GPU
        //     mutex serializes Narrate/Interpret/Synth across days; no
        //     parallelization opportunity.
        //   FULL_PIPELINE single day: delegates to DailyPipelineWorkflow to
        //     preserve cron contract (returns status string).
        //   FULL_PIPELINE multi-day: Stage 5 — IngestDay[N] runs sequentially
        //     (Postgres mutex); LlmDay[N] runs in parallel with
        //     IngestDay[N+1] (joi mutex + luv free during LLM). FinalizeDay[N]
        //     follows LlmDay[N]. Saves ~3-4 hr on a 12-day backfill.
        int daysProcessed;
        if (mode == Mode.LLM_CHAIN) {
            for (LocalDate date : effectiveDates) runLlmChainForDay(date);
            daysProcessed = effectiveDates.size();
        } else if (mode == Mode.SCORE_AND_LLM) {
            daysProcessed = runScoreAndLlm(effectiveDates);
        } else if (effectiveDates.size() == 1) {
            LocalDate date = effectiveDates.get(0);
            DailyPipelineWorkflow daily = Workflow.newChildWorkflowStub(
                    DailyPipelineWorkflow.class,
                    ChildWorkflowOptions.newBuilder()
                            .setWorkflowId("pipeline-daily-" + date + "-" + Workflow.getInfo().getWorkflowId())
                            .build());
            String status = daily.run(new DailyPipelineWorkflowInput(
                    date, input.pollUntilReady(), input.forceReingest(), input.runRetentionSweep()));
            LOG.info("daily done  date={} status={}", date, status);
            daysProcessed = 1;
        } else {
            daysProcessed = runSlidingWindow(effectiveDates, input);
        }

        // Rollup cascade. From the input dates, derive every touched
        // (week, quarter, year) period and fire its rollup workflow. Each
        // rollup is content-addressed at the activity level, so periods
        // whose inputs haven't changed (or whose gates aren't met for
        // quarter/year) cost essentially nothing.
        int weekly = 0, quarterly = 0, yearly = 0;
        if (input.cascadeRollups()) {
            CascadeScope scope = computeCascadeScope(effectiveDates);
            LOG.info("cascade scope  weeks={} quarters={} years={}",
                    scope.weekStarts.size(), scope.quarterStarts.size(), scope.yearStarts.size());

            for (LocalDate weekStart : scope.weekStarts) {
                AggregateWeekWorkflow w = Workflow.newChildWorkflowStub(
                        AggregateWeekWorkflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId("pipeline-week-" + weekStart + "-" + Workflow.getInfo().getWorkflowId())
                                .build());
                w.run(weekStart);
                weekly++;
            }
            for (LocalDate quarterStart : scope.quarterStarts) {
                AggregateQuarterWorkflow q = Workflow.newChildWorkflowStub(
                        AggregateQuarterWorkflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId("pipeline-quarter-" + quarterStart + "-" + Workflow.getInfo().getWorkflowId())
                                .build());
                q.run(quarterStart);
                quarterly++;
            }
            for (LocalDate yearStart : scope.yearStarts) {
                AggregateYearWorkflow y = Workflow.newChildWorkflowStub(
                        AggregateYearWorkflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId("pipeline-year-" + yearStart + "-" + Workflow.getInfo().getWorkflowId())
                                .build());
                y.run(yearStart);
                yearly++;
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        LOG.info("pipeline done  days={} weekly={} quarterly={} yearly={} elapsed_ms={}",
                daysProcessed, weekly, quarterly, yearly, elapsed);
        return new PipelineResult(daysProcessed, weekly, quarterly, yearly, elapsed);
    }

    /**
     * Stage 5 (2026-05-29) — Phase A/B overlap parallelization. For each day:
     * <ul>
     *   <li>IngestDay[N] runs after IngestDay[N-1] (sequential Postgres)
     *   <li>LlmDay[N] runs after IngestDay[N] AND LlmDay[N-1] (joi GPU mutex)
     *   <li>FinalizeDay[N] runs after LlmDay[N]
     * </ul>
     * Result: while LlmDay[N] hits joi, IngestDay[N+1] runs on luv. Saves
     * ~3-4 hr on a 12-day backfill; zero on a 1-day cron fire.
     */
    private int runSlidingWindow(final java.util.List<LocalDate> dates,
                                  final PipelineInput input) {
        // Resolve which stages to run. null = all 3.
        final java.util.Set<PipelineWorkflow.Stage> stages = (input.stages() == null)
                ? java.util.EnumSet.allOf(PipelineWorkflow.Stage.class)
                : input.stages();
        final boolean runIngest   = stages.contains(PipelineWorkflow.Stage.INGEST);
        final boolean runLlm      = stages.contains(PipelineWorkflow.Stage.LLM);
        final boolean runFinalize = stages.contains(PipelineWorkflow.Stage.FINALIZE);

        int n = dates.size();
        @SuppressWarnings("unchecked")
        io.temporal.workflow.Promise<String>[] ingestP = new io.temporal.workflow.Promise[n];
        @SuppressWarnings("unchecked")
        io.temporal.workflow.Promise<Long>[] llmP = new io.temporal.workflow.Promise[n];
        @SuppressWarnings("unchecked")
        io.temporal.workflow.Promise<Void>[] finP = new io.temporal.workflow.Promise[n];

        String parentId = Workflow.getInfo().getWorkflowId();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final LocalDate date = dates.get(i);

            // IngestDay[N]: serial across days (Postgres mutex). Skipped
            // entirely when stages excludes INGEST (caller is requesting
            // LLM/FINALIZE on already-ingested data).
            ingestP[i] = io.temporal.workflow.Async.function(() -> {
                if (idx > 0) ingestP[idx - 1].get();
                if (!runIngest) return "ok";  // synthetic — gates downstream as if ingest succeeded
                IngestDayWorkflow ing = Workflow.newChildWorkflowStub(
                        IngestDayWorkflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId("pipeline-ingest-" + date + "-" + parentId)
                                .build());
                return ing.run(new IngestDayWorkflow.Input(
                        date, input.pollUntilReady(), input.forceReingest()));
            });

            // LlmDay[N]: serial across days (joi mutex), but parallel with
            // next day's Ingest. Skipped when ingest short-circuits or when
            // stages excludes LLM.
            llmP[i] = io.temporal.workflow.Async.function(() -> {
                String status = ingestP[idx].get();
                if ("skipped_already_ingested".equals(status)
                        || "skipped_no_data".equals(status)
                        || "parse_failed".equals(status)) {
                    return 0L;
                }
                if (idx > 0) llmP[idx - 1].get();
                if (!runLlm) return 0L;
                LlmDayWorkflow llm = Workflow.newChildWorkflowStub(
                        LlmDayWorkflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId("pipeline-llm-" + date + "-" + parentId)
                                .build());
                return llm.run(date);
            });

            // FinalizeDay[N]: follows LlmDay[N]; independent across days
            // (compress + cleanup are per-day, no cross-day contention).
            // Skipped when stages excludes FINALIZE.
            finP[i] = io.temporal.workflow.Async.procedure(() -> {
                llmP[idx].get();
                if (!runFinalize) return;
                String status = ingestP[idx].get();
                boolean deleteFiles = input.runRetentionSweep() && "ok".equals(status);
                FinalizeDayWorkflow fin = Workflow.newChildWorkflowStub(
                        FinalizeDayWorkflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId("pipeline-finalize-" + date + "-" + parentId)
                                .build());
                fin.run(new FinalizeDayWorkflow.Input(
                        date, deleteFiles, input.runRetentionSweep()));
            });
        }

        // Drain all FinalizeDay promises (which depend on everything else).
        for (int i = 0; i < n; i++) {
            try { finP[i].get(); } catch (Exception e) {
                LOG.warn("sliding-window day failed  date={} err={}",
                        dates.get(i), e.getMessage());
            }
        }
        return n;
    }

    /**
     * Stage 6 (2026-05-29) — SCORE_AND_LLM mode. Replaces the bash
     * rescore-rerun chain: re-score each day (luv, sequential) then run
     * the LLM chain (joi, serial). Sliding window: Score[N+1] runs while
     * LlmDay[N] is on joi.
     */
    private int runScoreAndLlm(final java.util.List<LocalDate> dates) {
        int n = dates.size();
        @SuppressWarnings("unchecked")
        io.temporal.workflow.Promise<Long>[] scoreP = new io.temporal.workflow.Promise[n];
        @SuppressWarnings("unchecked")
        io.temporal.workflow.Promise<Long>[] llmP = new io.temporal.workflow.Promise[n];

        String parentId = Workflow.getInfo().getWorkflowId();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final LocalDate date = dates.get(i);

            scoreP[i] = io.temporal.workflow.Async.function(() -> {
                if (idx > 0) scoreP[idx - 1].get();
                ScoreWorkflow score = Workflow.newChildWorkflowStub(
                        ScoreWorkflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId("pipeline-score-" + date + "-" + parentId)
                                .build());
                return score.run(date);
            });

            llmP[i] = io.temporal.workflow.Async.function(() -> {
                scoreP[idx].get();
                if (idx > 0) llmP[idx - 1].get();
                LlmDayWorkflow llm = Workflow.newChildWorkflowStub(
                        LlmDayWorkflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId("pipeline-llm-" + date + "-" + parentId)
                                .build());
                return llm.run(date);
            });
        }

        for (int i = 0; i < n; i++) {
            try { llmP[i].get(); } catch (Exception e) {
                LOG.warn("score+llm day failed  date={} err={}",
                        dates.get(i), e.getMessage());
            }
        }
        return n;
    }

    /**
     * Run the LLM chain (Narrate → Interpret → SynthesizeDay) for a single
     * date via {@link LlmDayWorkflow}. Stage 2 (2026-05-29) — previously
     * inline; extracted so the chain is composable across cron, ad-hoc, and
     * the planned SCORE_AND_LLM mode.
     */
    private void runLlmChainForDay(final LocalDate date) {
        LlmDayWorkflow llm = Workflow.newChildWorkflowStub(
                LlmDayWorkflow.class,
                ChildWorkflowOptions.newBuilder()
                        .setWorkflowId("pipeline-llm-" + date + "-"
                                + Workflow.getInfo().getWorkflowId())
                        .build());
        llm.run(date);
    }

    /**
     * Stage 1 (2026-05-29): expand dates + dateRange into one ordered,
     * deduplicated, weekday-only list. Caller can pass either field or both
     * (union); IEX is closed on weekends, so Sat/Sun are excluded — pcap
     * doesn't exist for those days. Holidays pass through unchanged: the
     * per-day workflow short-circuits via {@code NotATradingDay} from
     * {@link com.longexposure.temporal.activities.ResolveUrlActivity}, so
     * we don't try to maintain a holiday calendar here.
     *
     * <p>Range expansion is inclusive of both endpoints. Order is
     * chronological (TreeSet → LinkedHashSet pattern matches
     * computeCascadeScope).
     */
    static java.util.List<LocalDate> expandDates(
            final java.util.List<LocalDate> explicit,
            final java.util.List<PipelineWorkflow.DateRange> ranges) {
        java.util.TreeSet<LocalDate> all = new java.util.TreeSet<>();
        if (explicit != null) {
            for (LocalDate d : explicit) {
                if (d != null && isWeekday(d)) all.add(d);
            }
        }
        if (ranges != null) {
            for (PipelineWorkflow.DateRange range : ranges) {
                if (range == null || range.from() == null || range.to() == null) continue;
                LocalDate from = range.from(), to = range.to();
                if (from.isAfter(to)) { LocalDate tmp = from; from = to; to = tmp; }
                for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                    if (isWeekday(d)) all.add(d);
                }
            }
        }
        return new java.util.ArrayList<>(all);
    }

    private static boolean isWeekday(final LocalDate d) {
        DayOfWeek dow = d.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    /**
     * Map a list of dates to the set of touched (week_start, quarter_start,
     * year_start) tuples. Each touched period gets its rollup fired in the
     * cascade. Sets are kept ordered (chronological) so the cascade fires
     * in a predictable order — useful for log readability + for the streak-
     * window verifier (prior weeks must be present before later weeks fire).
     */
    static CascadeScope computeCascadeScope(final java.util.List<LocalDate> dates) {
        Set<LocalDate> weeks = new TreeSet<>();
        Set<LocalDate> quarters = new TreeSet<>();
        Set<LocalDate> years = new TreeSet<>();
        for (LocalDate d : dates) {
            weeks.add(d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
            quarters.add(quarterStartOf(d));
            years.add(LocalDate.of(d.getYear(), Month.JANUARY, 1));
        }
        return new CascadeScope(
                new LinkedHashSet<>(weeks),
                new LinkedHashSet<>(quarters),
                new LinkedHashSet<>(years));
    }

    /** Calendar-quarter anchor for a date (Q1=Jan-Mar, Q2=Apr-Jun, ...). */
    private static LocalDate quarterStartOf(final LocalDate d) {
        int month = ((d.getMonthValue() - 1) / 3) * 3 + 1;
        return LocalDate.of(d.getYear(), month, 1);
    }

    /** Periods touched by the input date list — ordered chronologically. */
    record CascadeScope(
            Set<LocalDate> weekStarts,
            Set<LocalDate> quarterStarts,
            Set<LocalDate> yearStarts) {}
}
