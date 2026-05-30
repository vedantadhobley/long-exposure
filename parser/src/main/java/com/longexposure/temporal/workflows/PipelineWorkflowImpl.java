package com.longexposure.temporal.workflows;

import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.longexposure.temporal.workflows.PipelineWorkflow.Mode;
import static com.longexposure.temporal.workflows.PipelineWorkflow.Stage;

/**
 * Universal orchestrator. Resolves day-level + period-level inputs into one
 * scope, then drives a Promise DAG that respects two invariants:
 *
 * <ol>
 *   <li><b>Causality</b> — every weekly waits on its in-scope days, every
 *       quarterly waits on its in-scope weeks, every yearly waits on its
 *       in-scope quarters. Period N at any tier additionally waits on
 *       period N-1 at the same tier (the prior-window read pattern requires
 *       chronological ordering so a downstream rollup never reads stale
 *       upstream output).
 *   <li><b>Phase A/B overlap</b> — IngestDay (luv: CPU+Postgres) and LlmDay
 *       (joi: GPU mutex) run on disjoint resources. The DAG only forces
 *       cross-resource serialization where causality demands it, so
 *       IngestDay[N+1] consistently overlaps LlmDay[N].
 * </ol>
 *
 * <p>The cascade is <b>greedy</b>, not batched: AggregateWeek for week W
 * fires the moment the last in-scope day of W finishes its LLM chain
 * (subject to the prior-week-finishes-first rule). On joi the LLM mutex
 * makes wall-clock identical to a batched cascade, but the greedy ordering
 * means a downstream re-aggregate of week W+1 reads the FRESH output of W.
 */
public final class PipelineWorkflowImpl implements PipelineWorkflow {

    private static final Logger LOG = Workflow.getLogger(PipelineWorkflowImpl.class);

    @Override
    public PipelineResult run(final PipelineInput input) {
        long t0 = System.currentTimeMillis();
        Mode mode = (input.mode() == null) ? Mode.FULL_PIPELINE : input.mode();

        // ── Resolve every target field into normalized, deduplicated, chronologically-ordered sets.
        List<LocalDate> days = expandDates(input.dates(), input.dateRanges());
        Set<LocalDate> weeks    = expandAnchors(input.weekAnchors(),    PipelineWorkflowImpl::weekStartOf);
        Set<LocalDate> quarters = expandAnchors(input.quarterAnchors(), PipelineWorkflowImpl::quarterStartOf);
        Set<LocalDate> years    = expandAnchors(input.yearAnchors(),    PipelineWorkflowImpl::yearStartOf);

        // Horizontal ripple: walk every period from cascadeFrom forward through today (ET).
        if (input.cascadeFrom() != null) {
            LocalDate today = todayET();
            addWeeksRangeForward(weeks, input.cascadeFrom(), today);
            addQuartersRangeForward(quarters, input.cascadeFrom(), today);
            addYearsRangeForward(years, input.cascadeFrom(), today);
        }

        // Vertical cascade: days touch their containing week/quarter/year too.
        CascadeScope dayScope = computeCascadeScope(days);
        if (input.cascadeRollups()) {
            weeks.addAll(dayScope.weekStarts());
            quarters.addAll(dayScope.quarterStarts());
            years.addAll(dayScope.yearStarts());
        }

        if (days.isEmpty() && weeks.isEmpty() && quarters.isEmpty() && years.isEmpty()) {
            LOG.warn("pipeline start  no work in scope  dates={} dateRanges={} cascadeFrom={}",
                    input.dates(), input.dateRanges(), input.cascadeFrom());
            return new PipelineResult(0, 0, 0, 0, System.currentTimeMillis() - t0);
        }

        LOG.info("pipeline start  mode={} days={} weeks={} quarters={} years={} cascadeFrom={} cascadeRollups={} stages={}",
                mode, days.size(), weeks.size(), quarters.size(), years.size(),
                input.cascadeFrom(), input.cascadeRollups(), input.stages());

        // ── Phase A/B per-day work. The returned llmDone[i] resolves when day[i]'s
        // LlmDay completes; the cascade chains off these to slot weekly rollups
        // greedily without breaking the joi LLM-at-a-time rule.
        Promise<Long>[] llmDone = chainPerDayWork(days, input, mode);

        // ── Period rollups, greedy + chronological within each tier.
        Map<LocalDate, Promise<Long>> weeklyDone    = chainWeeklyRollups(weeks, days, llmDone);
        Map<LocalDate, Promise<Long>> quarterlyDone = chainQuarterlyRollups(quarters, weeks, weeklyDone);
        Map<LocalDate, Promise<Long>> yearlyDone    = chainYearlyRollups(years, quarters, quarterlyDone);

        // ── Drain everything. Failures logged but propagated upward as warnings;
        // the rollup tiers depend on each other only via Promise.get() and a
        // failed upstream Promise short-circuits the downstream get() with the
        // same exception.
        drainAll(days, llmDone, weeklyDone, quarterlyDone, yearlyDone);

        long elapsed = System.currentTimeMillis() - t0;
        LOG.info("pipeline done  days={} weekly={} quarterly={} yearly={} elapsed_ms={}",
                days.size(), weeklyDone.size(), quarterlyDone.size(), yearlyDone.size(), elapsed);
        return new PipelineResult(
                days.size(), weeklyDone.size(), quarterlyDone.size(), yearlyDone.size(), elapsed);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Per-day work (FULL_PIPELINE / LLM_CHAIN / SCORE_AND_LLM)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Build the per-day Promise chain based on mode. Returns the LLM-completion
     * promise for each day (in scope order), which the cascade chains off.
     * Empty array if {@code days} is empty.
     */
    @SuppressWarnings("unchecked")
    private Promise<Long>[] chainPerDayWork(final List<LocalDate> days,
                                             final PipelineInput input,
                                             final Mode mode) {
        if (days.isEmpty()) return new Promise[0];
        return switch (mode) {
            case LLM_CHAIN     -> chainLlmOnly(days);
            case SCORE_AND_LLM -> chainScoreAndLlm(days);
            case FULL_PIPELINE -> chainFullPipeline(days, input);
        };
    }

    /**
     * Full pipeline per day with Phase A/B sliding-window overlap and optional
     * {@link Stage} filter. IngestDay[N] runs after IngestDay[N-1] (Postgres
     * mutex); LlmDay[N] runs after IngestDay[N] AND LlmDay[N-1] (joi mutex);
     * FinalizeDay[N] follows LlmDay[N]. Result: LlmDay[N] on joi overlaps with
     * IngestDay[N+1] on luv.
     */
    @SuppressWarnings("unchecked")
    private Promise<Long>[] chainFullPipeline(final List<LocalDate> days, final PipelineInput input) {
        Set<Stage> stages = (input.stages() == null) ? EnumSet.allOf(Stage.class) : input.stages();
        boolean runIngest   = stages.contains(Stage.INGEST);
        boolean runLlm      = stages.contains(Stage.LLM);
        boolean runFinalize = stages.contains(Stage.FINALIZE);
        int n = days.size();
        Promise<String>[] ingestP = new Promise[n];
        Promise<Long>[]   llmP    = new Promise[n];
        Promise<Void>[]   finP    = new Promise[n];
        String parentId = Workflow.getInfo().getWorkflowId();

        for (int i = 0; i < n; i++) {
            final int idx = i;
            final LocalDate date = days.get(i);

            ingestP[i] = Async.function(() -> {
                if (idx > 0) ingestP[idx - 1].get();
                if (!runIngest) return "ok";  // synthetic — assume already ingested
                IngestDayWorkflow ing = Workflow.newChildWorkflowStub(
                        IngestDayWorkflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId("pipeline-ingest-" + date + "-" + parentId)
                                .build());
                return ing.run(new IngestDayWorkflow.Input(
                        date, input.pollUntilReady(), input.forceReingest()));
            });

            llmP[i] = Async.function(() -> {
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

            finP[i] = Async.procedure(() -> {
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
        // The cascade chains off llmP; finP runs in the background and is drained
        // alongside everything else in drainAll().
        return llmP;
    }

    /**
     * LLM-only chain: assumes upstream Score has already populated
     * {@code selected_events}. No ingest, no finalize. Days serialize on the
     * joi LLM mutex.
     */
    @SuppressWarnings("unchecked")
    private Promise<Long>[] chainLlmOnly(final List<LocalDate> days) {
        int n = days.size();
        Promise<Long>[] llmP = new Promise[n];
        String parentId = Workflow.getInfo().getWorkflowId();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final LocalDate date = days.get(i);
            llmP[i] = Async.function(() -> {
                if (idx > 0) llmP[idx - 1].get();
                LlmDayWorkflow llm = Workflow.newChildWorkflowStub(
                        LlmDayWorkflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId("pipeline-llm-" + date + "-" + parentId)
                                .build());
                return llm.run(date);
            });
        }
        return llmP;
    }

    /**
     * Score-then-LLM per day: re-runs ScoreWorkflow (sequential on Postgres)
     * then LlmDay (sequential on joi). Score[N+1] overlaps LlmDay[N] for the
     * same Phase A/B reason as FULL_PIPELINE.
     */
    @SuppressWarnings("unchecked")
    private Promise<Long>[] chainScoreAndLlm(final List<LocalDate> days) {
        int n = days.size();
        Promise<Long>[] scoreP = new Promise[n];
        Promise<Long>[] llmP   = new Promise[n];
        String parentId = Workflow.getInfo().getWorkflowId();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final LocalDate date = days.get(i);
            scoreP[i] = Async.function(() -> {
                if (idx > 0) scoreP[idx - 1].get();
                ScoreWorkflow score = Workflow.newChildWorkflowStub(
                        ScoreWorkflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId("pipeline-score-" + date + "-" + parentId)
                                .build());
                return score.run(date);
            });
            llmP[i] = Async.function(() -> {
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
        return llmP;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Cascade (weekly → quarterly → yearly)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Weekly rollups, greedy + chronological. Each AggregateWeek(W) waits on:
     * <ul>
     *   <li>the LlmDay promise for every in-scope day in W (so its inputs are fresh), AND
     *   <li>the previous in-scope weekly (so the prior-window read picks up the new version).
     * </ul>
     * Periods with no in-scope days still fire — the cascade walks them in
     * chronological order so the LLM queue stays loaded with downstream-eligible
     * work.
     */
    private Map<LocalDate, Promise<Long>> chainWeeklyRollups(
            final Set<LocalDate> weeks,
            final List<LocalDate> days,
            final Promise<Long>[] llmDone) {
        if (weeks.isEmpty()) return Map.of();
        // Bucket day indices by week-start for fast lookup.
        Map<LocalDate, List<Integer>> daysByWeek = new LinkedHashMap<>();
        for (int i = 0; i < days.size(); i++) {
            daysByWeek.computeIfAbsent(weekStartOf(days.get(i)), k -> new ArrayList<>()).add(i);
        }
        Map<LocalDate, Promise<Long>> out = new LinkedHashMap<>();
        String parentId = Workflow.getInfo().getWorkflowId();
        Promise<Long> prevWeek = null;
        for (LocalDate weekStart : weeks) {
            final LocalDate ws = weekStart;
            final List<Integer> daysInWeek = daysByWeek.getOrDefault(ws, List.of());
            final Promise<Long> prev = prevWeek;
            Promise<Long> p = Async.function(() -> {
                for (int idx : daysInWeek) llmDone[idx].get();
                if (prev != null) prev.get();
                AggregateWeekWorkflow w = Workflow.newChildWorkflowStub(
                        AggregateWeekWorkflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId("pipeline-week-" + ws + "-" + parentId)
                                .build());
                return w.run(ws);
            });
            out.put(ws, p);
            prevWeek = p;
        }
        return out;
    }

    /**
     * Quarterly rollups, greedy + chronological. Each AggregateQuarter(Q) waits
     * on every in-scope weekly that falls in Q AND on the previous quarterly.
     * If Q has no in-scope weeks, it still fires — chronological order
     * preserved for the prior-quarter read pattern.
     */
    private Map<LocalDate, Promise<Long>> chainQuarterlyRollups(
            final Set<LocalDate> quarters,
            final Set<LocalDate> weeks,
            final Map<LocalDate, Promise<Long>> weeklyDone) {
        if (quarters.isEmpty()) return Map.of();
        Map<LocalDate, List<LocalDate>> weeksByQuarter = new LinkedHashMap<>();
        for (LocalDate w : weeks) {
            weeksByQuarter.computeIfAbsent(quarterStartOf(w), k -> new ArrayList<>()).add(w);
        }
        Map<LocalDate, Promise<Long>> out = new LinkedHashMap<>();
        String parentId = Workflow.getInfo().getWorkflowId();
        Promise<Long> prevQuarter = null;
        for (LocalDate quarterStart : quarters) {
            final LocalDate qs = quarterStart;
            final List<LocalDate> weeksInQuarter = weeksByQuarter.getOrDefault(qs, List.of());
            final Promise<Long> prev = prevQuarter;
            Promise<Long> p = Async.function(() -> {
                for (LocalDate w : weeksInQuarter) weeklyDone.get(w).get();
                if (prev != null) prev.get();
                AggregateQuarterWorkflow q = Workflow.newChildWorkflowStub(
                        AggregateQuarterWorkflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId("pipeline-quarter-" + qs + "-" + parentId)
                                .build());
                return q.run(qs);
            });
            out.put(qs, p);
            prevQuarter = p;
        }
        return out;
    }

    /**
     * Yearly rollups, greedy + chronological. Each AggregateYear(Y) waits on
     * every in-scope quarterly that falls in Y AND on the previous yearly.
     */
    private Map<LocalDate, Promise<Long>> chainYearlyRollups(
            final Set<LocalDate> years,
            final Set<LocalDate> quarters,
            final Map<LocalDate, Promise<Long>> quarterlyDone) {
        if (years.isEmpty()) return Map.of();
        Map<LocalDate, List<LocalDate>> quartersByYear = new LinkedHashMap<>();
        for (LocalDate q : quarters) {
            quartersByYear.computeIfAbsent(yearStartOf(q), k -> new ArrayList<>()).add(q);
        }
        Map<LocalDate, Promise<Long>> out = new LinkedHashMap<>();
        String parentId = Workflow.getInfo().getWorkflowId();
        Promise<Long> prevYear = null;
        for (LocalDate yearStart : years) {
            final LocalDate ys = yearStart;
            final List<LocalDate> quartersInYear = quartersByYear.getOrDefault(ys, List.of());
            final Promise<Long> prev = prevYear;
            Promise<Long> p = Async.function(() -> {
                for (LocalDate q : quartersInYear) quarterlyDone.get(q).get();
                if (prev != null) prev.get();
                AggregateYearWorkflow y = Workflow.newChildWorkflowStub(
                        AggregateYearWorkflow.class,
                        ChildWorkflowOptions.newBuilder()
                                .setWorkflowId("pipeline-year-" + ys + "-" + parentId)
                                .build());
                return y.run(ys);
            });
            out.put(ys, p);
            prevYear = p;
        }
        return out;
    }

    /** Wait on every leaf promise; log failures rather than abort the workflow. */
    private void drainAll(final List<LocalDate> days,
                           final Promise<Long>[] llmDone,
                           final Map<LocalDate, Promise<Long>> weeklyDone,
                           final Map<LocalDate, Promise<Long>> quarterlyDone,
                           final Map<LocalDate, Promise<Long>> yearlyDone) {
        for (int i = 0; i < llmDone.length; i++) {
            try { llmDone[i].get(); } catch (Exception e) {
                LOG.warn("day llm failed  date={} err={}", days.get(i), e.getMessage());
            }
        }
        weeklyDone.forEach((ws, p) -> {
            try { p.get(); } catch (Exception e) {
                LOG.warn("weekly failed  week={} err={}", ws, e.getMessage());
            }
        });
        quarterlyDone.forEach((qs, p) -> {
            try { p.get(); } catch (Exception e) {
                LOG.warn("quarterly failed  quarter={} err={}", qs, e.getMessage());
            }
        });
        yearlyDone.forEach((ys, p) -> {
            try { p.get(); } catch (Exception e) {
                LOG.warn("yearly failed  year={} err={}", ys, e.getMessage());
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Helpers — date expansion + period normalization (pure, unit-tested)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Expand {@code dates} + {@code dateRanges} into one ordered, deduplicated,
     * weekday-only list. Holidays pass through; the per-day workflow
     * short-circuits on {@code NotATradingDay}.
     */
    static List<LocalDate> expandDates(
            final List<LocalDate> explicit,
            final List<PipelineWorkflow.DateRange> ranges) {
        TreeSet<LocalDate> all = new TreeSet<>();
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
        return new ArrayList<>(all);
    }

    /**
     * Normalize a list of "any date in the period" anchors to canonical period
     * starts (Monday for week, Q-start for quarter, Jan 1 for year). Dedup
     * + chronological order via TreeSet, then LinkedHashSet for stable order.
     */
    static Set<LocalDate> expandAnchors(
            final List<LocalDate> anchors,
            final java.util.function.Function<LocalDate, LocalDate> normalize) {
        if (anchors == null || anchors.isEmpty()) return new TreeSet<>();
        TreeSet<LocalDate> out = new TreeSet<>();
        for (LocalDate a : anchors) {
            if (a != null) out.add(normalize.apply(a));
        }
        return out;
    }

    /** Add every Monday from {@code weekStartOf(from)} to {@code weekStartOf(throughInclusive)}. */
    static void addWeeksRangeForward(final Set<LocalDate> out,
                                      final LocalDate from,
                                      final LocalDate throughInclusive) {
        LocalDate w = weekStartOf(from);
        LocalDate end = weekStartOf(throughInclusive);
        while (!w.isAfter(end)) {
            out.add(w);
            w = w.plusWeeks(1);
        }
    }

    /** Add every quarter-start from {@code from}'s quarter to {@code throughInclusive}'s. */
    static void addQuartersRangeForward(final Set<LocalDate> out,
                                         final LocalDate from,
                                         final LocalDate throughInclusive) {
        LocalDate q = quarterStartOf(from);
        LocalDate end = quarterStartOf(throughInclusive);
        while (!q.isAfter(end)) {
            out.add(q);
            q = q.plusMonths(3);
        }
    }

    /** Add every year-start from {@code from}'s year to {@code throughInclusive}'s. */
    static void addYearsRangeForward(final Set<LocalDate> out,
                                      final LocalDate from,
                                      final LocalDate throughInclusive) {
        LocalDate y = yearStartOf(from);
        LocalDate end = yearStartOf(throughInclusive);
        while (!y.isAfter(end)) {
            out.add(y);
            y = y.plusYears(1);
        }
    }

    /** Today in ET via {@code Workflow.currentTimeMillis} (deterministic for replay). */
    private static LocalDate todayET() {
        return Instant.ofEpochMilli(Workflow.currentTimeMillis())
                .atZone(ZoneId.of("America/New_York")).toLocalDate();
    }

    private static boolean isWeekday(final LocalDate d) {
        DayOfWeek dow = d.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    /**
     * Map a list of dates to the (week_start, quarter_start, year_start)
     * periods they touch. Returned sets are chronological so cascade loops
     * walk them in prior-window order.
     */
    static CascadeScope computeCascadeScope(final List<LocalDate> dates) {
        Set<LocalDate> weeks = new TreeSet<>();
        Set<LocalDate> quarters = new TreeSet<>();
        Set<LocalDate> years = new TreeSet<>();
        for (LocalDate d : dates) {
            weeks.add(weekStartOf(d));
            quarters.add(quarterStartOf(d));
            years.add(yearStartOf(d));
        }
        return new CascadeScope(
                new LinkedHashSet<>(weeks),
                new LinkedHashSet<>(quarters),
                new LinkedHashSet<>(years));
    }

    /** Monday anchor for the ISO week containing {@code d}. */
    static LocalDate weekStartOf(final LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** Calendar-quarter anchor for {@code d} (Q1=Jan, Q2=Apr, Q3=Jul, Q4=Oct). */
    static LocalDate quarterStartOf(final LocalDate d) {
        int month = ((d.getMonthValue() - 1) / 3) * 3 + 1;
        return LocalDate.of(d.getYear(), month, 1);
    }

    /** Jan 1 of {@code d}'s year. */
    static LocalDate yearStartOf(final LocalDate d) {
        return LocalDate.of(d.getYear(), Month.JANUARY, 1);
    }

    /** Periods touched by the day-level scope — ordered chronologically. */
    record CascadeScope(
            Set<LocalDate> weekStarts,
            Set<LocalDate> quarterStarts,
            Set<LocalDate> yearStarts) {}
}
