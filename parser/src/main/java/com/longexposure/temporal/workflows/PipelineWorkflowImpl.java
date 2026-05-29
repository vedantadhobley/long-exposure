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

public final class PipelineWorkflowImpl implements PipelineWorkflow {

    private static final Logger LOG = Workflow.getLogger(PipelineWorkflowImpl.class);

    @Override
    public PipelineResult run(final PipelineInput input) {
        long t0 = System.currentTimeMillis();
        LOG.info("pipeline start  dates={} cascade={} forceReingest={} retentionSweep={}",
                input.dates(), input.cascadeRollups(), input.forceReingest(), input.runRetentionSweep());

        // Per-day work. Each date fires DailyPipelineWorkflow as a child.
        // Sequential in v1 — parallelization (Phase A overlap) is a planned
        // follow-up that requires either splitting DailyPipelineWorkflow into
        // separately-callable phase workflows or having PipelineWorkflow call
        // the individual stage workflows directly.
        int daysProcessed = 0;
        for (LocalDate date : input.dates()) {
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
            daysProcessed++;
        }

        // Rollup cascade. From the input dates, derive every touched
        // (week, quarter, year) period and fire its rollup workflow. Each
        // rollup is content-addressed at the activity level, so periods
        // whose inputs haven't changed (or whose gates aren't met for
        // quarter/year) cost essentially nothing.
        int weekly = 0, quarterly = 0, yearly = 0;
        if (input.cascadeRollups()) {
            CascadeScope scope = computeCascadeScope(input.dates());
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
