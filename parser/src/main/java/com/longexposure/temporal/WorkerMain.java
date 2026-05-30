package com.longexposure.temporal;

import com.longexposure.temporal.activities.CleanupFilesActivityImpl;
import com.longexposure.temporal.activities.CompressChunksActivityImpl;
import com.longexposure.temporal.activities.DeepTopsValidatorActivityImpl;
import com.longexposure.temporal.activities.DownloadFileActivityImpl;
import com.longexposure.temporal.activities.DplsDeepValidatorActivityImpl;
import com.longexposure.temporal.activities.DplsTopsValidatorActivityImpl;
import com.longexposure.temporal.activities.MaterializeOrderLifecycleActivityImpl;
import com.longexposure.temporal.activities.ParseAndWriteDplsActivityImpl;
import com.longexposure.temporal.activities.PipelineRunRecorderActivityImpl;
import com.longexposure.temporal.activities.PruneStaleNarrationsActivityImpl;
import com.longexposure.temporal.activities.RetainRawFilesActivityImpl;
import com.longexposure.temporal.activities.EnrichAnalyticsActivityImpl;
import com.longexposure.temporal.activities.EnrichWithCoOccurrenceActivityImpl;
import com.longexposure.temporal.activities.ListSelectedEventsActivityImpl;
import com.longexposure.temporal.activities.InterpretEventActivityImpl;
import com.longexposure.temporal.activities.NarrateEventActivityImpl;
import com.longexposure.temporal.activities.SynthesizeDayActivityImpl;
import com.longexposure.temporal.activities.AggregateWeekActivityImpl;
import com.longexposure.temporal.activities.AggregateQuarterActivityImpl;
import com.longexposure.temporal.activities.AggregateYearActivityImpl;
import com.longexposure.temporal.activities.RecordValidationActivityImpl;
import com.longexposure.temporal.activities.RefreshBaselinesActivityImpl;
import com.longexposure.temporal.activities.RefreshSymbolMetadataActivityImpl;
import com.longexposure.temporal.activities.ResolveUrlActivityImpl;
import com.longexposure.temporal.activities.RetentionSweepActivityImpl;
import com.longexposure.temporal.activities.ScoreEventsActivityImpl;
import com.longexposure.temporal.activities.SelectTopEventsActivityImpl;
import com.longexposure.temporal.workflows.CleanupWorkflowImpl;
import com.longexposure.temporal.workflows.DailyPipelineWorkflow;
import com.longexposure.temporal.workflows.DailyPipelineWorkflowImpl;
import com.longexposure.temporal.workflows.DailyPipelineWorkflowInput;
import com.longexposure.temporal.workflows.DownloadWorkflowImpl;
import com.longexposure.temporal.workflows.MaterializeWorkflowImpl;
import com.longexposure.temporal.workflows.InterpretWorkflowImpl;
import com.longexposure.temporal.workflows.NarrateWorkflowImpl;
import com.longexposure.temporal.workflows.SynthesizeDayWorkflowImpl;
import com.longexposure.temporal.workflows.AggregateWeekWorkflowImpl;
import com.longexposure.temporal.workflows.AggregateQuarterWorkflowImpl;
import com.longexposure.temporal.workflows.AggregateYearWorkflowImpl;
import com.longexposure.temporal.workflows.ParseWorkflowImpl;
import com.longexposure.temporal.workflows.FinalizeDayWorkflowImpl;
import com.longexposure.temporal.workflows.IngestDayWorkflowImpl;
import com.longexposure.temporal.workflows.LlmDayWorkflowImpl;
import com.longexposure.temporal.workflows.PipelineWorkflowImpl;
import com.longexposure.temporal.workflows.RefreshSymbolsWorkflow;
import com.longexposure.temporal.workflows.RefreshSymbolsWorkflowImpl;
import com.longexposure.temporal.workflows.ScoreWorkflowImpl;
import com.longexposure.temporal.workflows.SelectWorkflowImpl;
import com.longexposure.temporal.workflows.ValidateWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleAlreadyRunningException;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.SchedulePolicy;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.client.schedules.ScheduleState;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.interceptors.ScheduleClientCallsInterceptor;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Temporal worker entry point + schedule registration.
 *
 * <p>Run via {@code start()} when no smoke-test file is configured.
 * Registers {@link DailyPipelineWorkflow} + every activity impl on
 * {@link DailyPipelineWorkflow#TASK_QUEUE}, then registers a cron
 * schedule that fires at 00:00 America/New_York on Tue–Sat (T+1 of
 * a trading session).
 */
public final class WorkerMain {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerMain.class);

    /** Schedule ID. Same name used by every worker — schedule is global. */
    public static final String SCHEDULE_ID = "daily-pipeline-cron";

    /** Weekly schedule for refreshing the symbols reference table. */
    public static final String SYMBOLS_SCHEDULE_ID = "refresh-symbols-weekly";

    private WorkerMain() {}

    public static void start() {
        String temporalHost = System.getenv().getOrDefault("TEMPORAL_HOST", "long-exposure-dev-temporal:7233");
        LOG.info("connecting to Temporal  host={}", temporalHost);

        WorkflowServiceStubs service = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(temporalHost)
                        .build());

        WorkflowClient client = WorkflowClient.newInstance(service);
        WorkerFactory factory = WorkerFactory.newInstance(client);

        // Main worker: everything except the LLM-bound narration activity.
        // Uses Temporal's default concurrency (200 activity slots), which
        // is fine because none of these activities saturate a shared
        // bottleneck.
        Worker worker = factory.newWorker(DailyPipelineWorkflow.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(
                PipelineWorkflowImpl.class,
                IngestDayWorkflowImpl.class,
                LlmDayWorkflowImpl.class,
                FinalizeDayWorkflowImpl.class,
                DailyPipelineWorkflowImpl.class,
                DownloadWorkflowImpl.class,
                ParseWorkflowImpl.class,
                ValidateWorkflowImpl.class,
                MaterializeWorkflowImpl.class,
                ScoreWorkflowImpl.class,
                SelectWorkflowImpl.class,
                NarrateWorkflowImpl.class,
                InterpretWorkflowImpl.class,
                SynthesizeDayWorkflowImpl.class,
                AggregateWeekWorkflowImpl.class,
                AggregateQuarterWorkflowImpl.class,
                AggregateYearWorkflowImpl.class,
                CleanupWorkflowImpl.class,
                RefreshSymbolsWorkflowImpl.class);
        worker.registerActivitiesImplementations(
                new ResolveUrlActivityImpl(),
                new DownloadFileActivityImpl(),
                new ParseAndWriteDplsActivityImpl(),
                new DplsDeepValidatorActivityImpl(),
                new DplsTopsValidatorActivityImpl(),
                new DeepTopsValidatorActivityImpl(),
                new RecordValidationActivityImpl(),
                new RefreshBaselinesActivityImpl(),
                new MaterializeOrderLifecycleActivityImpl(),
                new ScoreEventsActivityImpl(),
                new EnrichWithCoOccurrenceActivityImpl(),
                new SelectTopEventsActivityImpl(),
                new EnrichAnalyticsActivityImpl(),
                new ListSelectedEventsActivityImpl(),
                new CleanupFilesActivityImpl(),
                new CompressChunksActivityImpl(),
                new RetentionSweepActivityImpl(),
                new PruneStaleNarrationsActivityImpl(),
                new RetainRawFilesActivityImpl(),
                new PipelineRunRecorderActivityImpl(),
                new RefreshSymbolMetadataActivityImpl());

        // Dedicated narration worker: a second worker pool on a separate
        // task queue with max concurrent execution = 2. Caps LLM
        // concurrency at the Temporal-dispatch level so when
        // NarrateWorkflow fans out 90 activities, only 2 are pulled
        // into local execution; the other 88 sit in Temporal's queue
        // and their start-to-close timer doesn't begin until they're
        // actually dispatched. The LlamaClient JVM-wide Semaphore(2)
        // remains as second-line defense.
        Worker narrationWorker = factory.newWorker(
                DailyPipelineWorkflow.NARRATION_TASK_QUEUE,
                io.temporal.worker.WorkerOptions.newBuilder()
                        .setMaxConcurrentActivityExecutionSize(2)
                        .build());
        narrationWorker.registerActivitiesImplementations(
                new NarrateEventActivityImpl(),
                new InterpretEventActivityImpl(),
                new SynthesizeDayActivityImpl(),
                new AggregateWeekActivityImpl(),
                new AggregateQuarterActivityImpl(),
                new AggregateYearActivityImpl());

        factory.start();
        LOG.info("workers started  main_queue={} narration_queue={} narration_concurrency=2",
                DailyPipelineWorkflow.TASK_QUEUE,
                DailyPipelineWorkflow.NARRATION_TASK_QUEUE);

        // Schedule registration is best-effort: if it fails, the worker
        // still runs and ad-hoc executions work.
        try {
            registerSchedule(client);
        } catch (Exception e) {
            LOG.warn("schedule registration failed (worker continues running)", e);
        }
        try {
            registerSymbolsRefreshSchedule(client);
        } catch (Exception e) {
            LOG.warn("symbols-refresh schedule registration failed (worker continues running)", e);
        }

        // Block forever — Temporal's worker threads keep running.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Register the cron schedule (idempotent — if it already exists,
     * skip and log).
     *
     * <p>Spec: 00:00 America/New_York, Tue–Sat. Tue–Sat because each run
     * processes T-1's trading day (Mon's data on Tue, Fri's on Sat).
     * Trading-day detection happens inside the workflow via
     * {@code ResolveUrlActivity.NotATradingDay}, so a Sat run on a
     * holiday Friday just short-circuits to {@code skipped_no_data}.
     */
    private static void registerSchedule(final WorkflowClient client) {
        ScheduleClient scheduleClient = ScheduleClient.newInstance(client.getWorkflowServiceStubs());

        // Workflow input: a stub date is built at fire time below via the
        // Action's argument list — Temporal will pass an empty
        // DailyPipelineWorkflowInput-shaped argument here unless we close
        // over a concrete date. We hand it a placeholder and rely on a
        // wrapper workflow approach: since we can't compute "yesterday"
        // declaratively in the schedule, we register a self-contained
        // schedule that produces a runnable starter workflow.
        //
        // Pragmatic Sprint 1 shape: use cron + a fixed-input action that
        // passes a LocalDate.MIN placeholder; the workflow checks if its
        // input is the placeholder and resolves "today_et - 1 day" at
        // entry. (Future cleanup: a separate Cron schedule that calls a
        // shim activity to compute the date.)
        LocalDate placeholder = LocalDate.of(1970, 1, 1);
        // Cron fires PipelineWorkflow (the universal entry point) — internally
        // delegates to DailyPipelineWorkflow for single-day FULL_PIPELINE,
        // preserving the cron contract exactly. Using PipelineWorkflow here
        // keeps every entry point (cron, ad-hoc, multi-day backfill,
        // LLM-only re-runs) on a single workflow type.
        com.longexposure.temporal.workflows.PipelineWorkflow.PipelineInput input =
                new com.longexposure.temporal.workflows.PipelineWorkflow.PipelineInput(
                        List.of(placeholder),    // dates
                        null,                     // dateRanges
                        true,                     // pollUntilReady — cron HIST poll
                        false,                    // forceReingest
                        true,                     // runRetentionSweep
                        true,                     // cascadeRollups
                        com.longexposure.temporal.workflows.PipelineWorkflow.Mode.FULL_PIPELINE);

        String workflowIdBase = "pipeline-scheduled";
        ScheduleActionStartWorkflow action = ScheduleActionStartWorkflow.newBuilder()
                .setWorkflowType(com.longexposure.temporal.workflows.PipelineWorkflow.class)
                .setArguments(input)
                .setOptions(WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowIdBase)
                        .setTaskQueue(DailyPipelineWorkflow.TASK_QUEUE)
                        .build())
                .build();

        ScheduleSpec spec = ScheduleSpec.newBuilder()
                .setCronExpressions(List.of("0 0 * * 2-6"))   // 00:00 Tue–Sat
                .setTimeZoneName("America/New_York")
                .build();

        Schedule schedule = Schedule.newBuilder()
                .setAction(action)
                .setSpec(spec)
                .setPolicy(SchedulePolicy.newBuilder()
                        .setOverlap(io.temporal.api.enums.v1.ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)
                        .build())
                // Register paused. Operator unpauses via
                //   docker exec long-exposure-dev-temporal \
                //     temporal schedule toggle --schedule-id daily-pipeline-cron --unpause
                // when ready to start nightly ingestion. Avoids dev-time
                // surprises (e.g. an in-flight manual workflow getting
                // doubled up by a cron fire at midnight ET).
                .setState(ScheduleState.newBuilder()
                        .setPaused(true)
                        .setNote("paused by default at registration — operator unpauses to enable nightly cron")
                        .build())
                .build();

        try {
            scheduleClient.createSchedule(SCHEDULE_ID, schedule, ScheduleOptions.newBuilder().build());
            LOG.info("schedule registered  id={} spec='0 0 * * 2-6 America/New_York'", SCHEDULE_ID);
        } catch (ScheduleAlreadyRunningException already) {
            LOG.info("schedule already exists — leaving as-is  id={}", SCHEDULE_ID);
        }
    }

    /**
     * Register a weekly schedule that runs {@link RefreshSymbolsWorkflow}
     * every Sunday at 02:00 America/New_York. Sunday because markets are
     * closed and the nightly daily-pipeline cron can't fire at the same
     * time. Idempotent — leaves an existing schedule alone.
     *
     * <p>Registered paused-by-default like the daily-pipeline schedule;
     * unpause with:
     * <pre>
     *   docker exec long-exposure-dev-temporal temporal schedule toggle \
     *     --schedule-id refresh-symbols-weekly --unpause
     * </pre>
     * For initial population, run it manually once:
     * <pre>
     *   docker exec long-exposure-dev-temporal temporal schedule trigger \
     *     --schedule-id refresh-symbols-weekly
     * </pre>
     */
    private static void registerSymbolsRefreshSchedule(final WorkflowClient client) {
        ScheduleClient scheduleClient = ScheduleClient.newInstance(client.getWorkflowServiceStubs());

        ScheduleActionStartWorkflow action = ScheduleActionStartWorkflow.newBuilder()
                .setWorkflowType(RefreshSymbolsWorkflow.class)
                .setOptions(WorkflowOptions.newBuilder()
                        .setWorkflowId(RefreshSymbolsWorkflow.WORKFLOW_ID)
                        .setTaskQueue(RefreshSymbolsWorkflow.TASK_QUEUE)
                        .build())
                .build();

        ScheduleSpec spec = ScheduleSpec.newBuilder()
                .setCronExpressions(List.of("0 2 * * 0"))   // 02:00 Sun
                .setTimeZoneName("America/New_York")
                .build();

        Schedule schedule = Schedule.newBuilder()
                .setAction(action)
                .setSpec(spec)
                .setPolicy(SchedulePolicy.newBuilder()
                        .setOverlap(io.temporal.api.enums.v1.ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)
                        .build())
                .setState(ScheduleState.newBuilder()
                        .setPaused(true)
                        .setNote("paused by default — operator runs manually for initial population then unpauses for weekly")
                        .build())
                .build();

        try {
            scheduleClient.createSchedule(SYMBOLS_SCHEDULE_ID, schedule, ScheduleOptions.newBuilder().build());
            LOG.info("symbols-refresh schedule registered  id={} spec='0 2 * * 0 America/New_York'", SYMBOLS_SCHEDULE_ID);
        } catch (ScheduleAlreadyRunningException already) {
            LOG.info("symbols-refresh schedule already exists — leaving as-is  id={}", SYMBOLS_SCHEDULE_ID);
        }
    }

    /** Returns the current trading-day target: previous calendar day in ET. */
    public static LocalDate yesterdayInET() {
        ZonedDateTime nowET = ZonedDateTime.now(ZoneId.of("America/New_York"));
        return nowET.toLocalDate().minusDays(1);
    }
}
