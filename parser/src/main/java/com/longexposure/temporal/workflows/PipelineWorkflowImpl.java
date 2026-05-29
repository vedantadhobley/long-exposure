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

        // Stage 1: expand dates + dateRange into an ordered, deduplicated,
        // weekday-only list. Caller can pass either field or both (union).
        java.util.List<LocalDate> effectiveDates = expandDates(
                input.dates(), input.dateRange());
        if (effectiveDates.isEmpty()) {
            LOG.warn("pipeline start  no effective dates  dates={} dateRange={}",
                    input.dates(), input.dateRange());
            return new PipelineResult(0, 0, 0, 0, System.currentTimeMillis() - t0);
        }

        LOG.info("pipeline start  mode={} dates_count={} (from dates={} range={}) cascade={} forceReingest={} retentionSweep={}",
                mode, effectiveDates.size(),
                input.dates() == null ? 0 : input.dates().size(),
                input.dateRange(),
                input.cascadeRollups(), input.forceReingest(), input.runRetentionSweep());

        // Per-day work — dispatched by mode.
        //   FULL_PIPELINE: fires DailyPipelineWorkflow per date (existing
        //     behavior; respects status='ok' idempotency guard).
        //   LLM_CHAIN:     fires Narrate → Interpret → Synthesize per date
        //     directly, skipping the parse/score guard. Used for re-runs
        //     after a PROMPT_VERSION bump where the wire-format tables +
        //     selected_events are already populated but the LLM stages need
        //     to re-run. Eliminates the bash-driver chain that
        //     scripts/catchup-*.sh used to wrap.
        //
        // Sequential in v1 — parallelization (Phase A overlap) is a planned
        // follow-up (see docs/phase8-v2-parallelization.md).
        int daysProcessed = 0;
        for (LocalDate date : effectiveDates) {
            if (mode == Mode.LLM_CHAIN) {
                runLlmChainForDay(date);
            } else {
                DailyPipelineWorkflow daily = Workflow.newChildWorkflowStub(
                        DailyPipelineWorkflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId("pipeline-daily-" + date + "-" + Workflow.getInfo().getWorkflowId())
                                .build());
                DailyPipelineWorkflowInput dailyInput = new DailyPipelineWorkflowInput(
                        date,
                        input.pollUntilReady(),
                        input.forceReingest(),
                        input.runRetentionSweep());
                String status = daily.run(dailyInput);
                LOG.info("daily done  date={} status={}", date, status);
            }
            daysProcessed++;
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
     * Run the LLM chain (Narrate → Interpret → SynthesizeDay) for a single
     * date, strictly sequential per the one-LLM-workflow-at-a-time rule. All
     * three workflows are content-addressed so unchanged inputs no-op cheaply;
     * a PROMPT_VERSION bump invalidates the cache and forces a fresh LLM run.
     */
    private void runLlmChainForDay(final LocalDate date) {
        String wfid = Workflow.getInfo().getWorkflowId();

        NarrateWorkflow narrate = Workflow.newChildWorkflowStub(
                NarrateWorkflow.class,
                ChildWorkflowOptions.newBuilder()
                        .setWorkflowId("pipeline-narrate-" + date + "-" + wfid)
                        .build());
        long narrated = narrate.run(date);
        LOG.info("llm-chain narrate done  date={} narrated={}", date, narrated);

        InterpretWorkflow interpret = Workflow.newChildWorkflowStub(
                InterpretWorkflow.class,
                ChildWorkflowOptions.newBuilder()
                        .setWorkflowId("pipeline-interpret-" + date + "-" + wfid)
                        .build());
        long interpreted = interpret.run(date);
        LOG.info("llm-chain interpret done  date={} interpreted={}", date, interpreted);

        SynthesizeDayWorkflow synth = Workflow.newChildWorkflowStub(
                SynthesizeDayWorkflow.class,
                ChildWorkflowOptions.newBuilder()
                        .setWorkflowId("pipeline-synth-" + date + "-" + wfid)
                        .build());
        synth.run(date);
        LOG.info("llm-chain synth done  date={}", date);
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
            final PipelineWorkflow.DateRange range) {
        java.util.TreeSet<LocalDate> all = new java.util.TreeSet<>();
        if (explicit != null) {
            for (LocalDate d : explicit) {
                if (d != null && isWeekday(d)) all.add(d);
            }
        }
        if (range != null && range.from() != null && range.to() != null) {
            LocalDate from = range.from(), to = range.to();
            if (from.isAfter(to)) { LocalDate tmp = from; from = to; to = tmp; }
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                if (isWeekday(d)) all.add(d);
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
