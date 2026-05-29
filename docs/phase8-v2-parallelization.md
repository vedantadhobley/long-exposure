# Phase 8 v2 — PipelineWorkflow Phase A / B overlap parallelization

> **Status: deferred (post-launch).** v1 ships sequential per-day execution
> (commit `1352dcb`, 2026-05-29). v2 is pure orchestration — no new
> activities, no schema changes, no behavioral change per event. Only
> wall-clock savings on multi-day backfills.

## Why

Per-day pipeline splits cleanly along the resource boundary between
luv (CPU + Postgres) and joi (single GPU, LLM mutex):

| Phase | Where | LLM? | Why parallelizable |
|---|---|---|---|
| **Phase A**: download → parse → validate → materialize → score → enrich → select | luv | no | CPU + Postgres-bound; doesn't touch joi |
| **Phase B**: DESCRIBE → INTERPRET → SYNTHESIZE | joi | yes | Bound by `LlamaClient.Semaphore(2, fair)` JVM-wide mutex |

Phase A of day N+1 has zero resource conflict with Phase B of day N.
Running them sequentially leaves joi idle during Phase A and luv idle
during Phase B. Pipelining recovers both.

```
Day N:    A───────B────────────────
Day N+1:           A───────B─────────────
Day N+2:                    A───────B─────────────
Day N+3:                             A───────B─────────────
                                              ↓
                                         Cascade (sequential at end)
```

Estimated saving on a 12-day backfill: **~3-4 hr** (Phase A time hidden
behind Phase B time of the prior day). **Zero saving on a 1-day cron**
fire — there's nothing to overlap.

## What changes (orchestration only)

### Split `DailyPipelineWorkflow` into two child workflows

```java
public interface PhaseAWorkflow {
    @WorkflowMethod
    PhaseAResult run(LocalDate date, DailyPipelineWorkflowInput input);
}

public interface PhaseBWorkflow {
    @WorkflowMethod
    PhaseBResult run(LocalDate date, DailyPipelineWorkflowInput input);
}
```

`DailyPipelineWorkflow` becomes a thin wrapper that runs `PhaseA` then
`PhaseB` sequentially (preserves cron's per-day semantics). The existing
phase workflows (`Download`, `Parse`, `Validate`, ..., `SynthesizeDay`)
are unchanged — they're called from inside `PhaseA` and `PhaseB`.

### `PipelineWorkflow.run()` becomes a sliding window

```java
List<Promise<PhaseAResult>> phaseAByDay = new ArrayList<>(dates.size());
List<Promise<PhaseBResult>> phaseBByDay = new ArrayList<>(dates.size());

for (int i = 0; i < dates.size(); i++) {
    LocalDate date = dates.get(i);

    // Phase A: depends only on the previous day's Phase A finishing
    // (sequential Postgres; no point parallelizing — would compete for
    // shared_buffers + work_mem).
    Promise<PhaseAResult> a = Async.function(phaseA::run, date, input);
    if (i > 0) phaseAByDay.get(i - 1).get();  // wait for prior A
    phaseAByDay.add(a);

    // Phase B: depends on THIS day's Phase A AND the prior day's
    // Phase B (LLM mutex serializes B across days).
    Promise<PhaseBResult> b = Async.function(() -> {
        a.get();                                  // this day's A done
        if (i > 0) phaseBByDay.get(i - 1).get();  // prior B done
        return phaseB.run(date, input);
    });
    phaseBByDay.add(b);
}

// Drain all
for (Promise<PhaseAResult> p : phaseAByDay) p.get();
for (Promise<PhaseBResult> p : phaseBByDay) p.get();

// Cascade unchanged — fires after everything done.
if (input.cascadeRollups()) runRollupCascade(...);
```

### Failure semantics

- **Phase A failure**: blocks that day's Phase B (which `.get()`s on
  Phase A). Other days' Phase A continue.
- **Phase B failure**: blocks subsequent days' Phase B (LLM-mutex chain).
  Earlier days' Phase B already complete. Phase A continues to run.
- **Both partial**: `PipelineResult` returns counts of completed days
  plus a failure list. Re-running with the same dates is safe — content-
  addressed activities skip whatever's already done.

## Constraints / risks

1. **Postgres back-pressure**: Phase A of day N+1 starts while day N's
   pcap parse may still be writing to wire-format hypertables. The
   `mem_limit: 48g` on the dev postgres handles 2 concurrent parses
   comfortably, but **3+ concurrent parses on a 12-day backfill** would
   blow memory. Mitigate: cap concurrent Phase A to ~2 via a permit.
2. **joi mutex still serializes B across days**: the LLM activity-level
   `Semaphore(2, fair)` doesn't know about workflows. Two Phase B
   instances scheduled simultaneously will both spawn activities that
   wait on the semaphore, creating Temporal UI noise (lots of
   "scheduled but pending"). Mitigate: workflow-side `Mutex` (one
   permit) shared across Phase B instances.
3. **Cascade still serial**: weekly/quarterly/yearly aggregates run
   after all Phase B done. Cannot interleave (they're LLM-bound).

## Acceptance criteria

- 12-day backfill via `PipelineWorkflow(dates=[...], cascadeRollups=true)`
  completes in ~7-8 hr instead of ~12 hr (the ~3-4 hr saving above).
- 1-day cron fire (`PipelineWorkflow(dates=[yesterday-placeholder])`)
  completes in same wall-clock as v1 — no overhead from the sliding
  window.
- Existing per-phase ad-hoc workflows (`NarrateWorkflow`, etc.)
  continue to work standalone.
- `PipelineWorkflowCascadeTest` still passes (cascade-scope is
  unchanged).

## Not in scope

- Cross-host parallelism (joi already runs 2 concurrent decode streams;
  going wider needs more GPUs, not more orchestration).
- Per-event parallelism (already shipped via fan-out in `NarrateWorkflow`
  / `InterpretWorkflow` sliding window).
- Quarterly + yearly cascade parallelism (they're gated; almost always
  no-op; no value in parallelizing).
