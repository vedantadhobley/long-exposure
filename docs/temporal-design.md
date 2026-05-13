# Temporal pipeline design

What the nightly ingest workflow does, how it handles trading-day detection,
retries, validation, and cleanup. Written before any Temporal code lands —
this is the design we agreed on; the code will follow it.

## Scope

One workflow class, two operating modes:

- **Cron mode** — fires nightly at midnight ET (Tue–Sat in `America/New_York`)
  to ingest the previous trading day's data. Polls IEX HIST for files until
  they're available.
- **Ad-hoc mode** — manually invoked from Temporal UI with a target date.
  Runs the same downstream steps but **skips the polling logic** (the file
  either exists at IEX HIST already, or it doesn't — no point waiting).

Both modes share the same workflow code. The difference is a single
`poll_until_ready: bool` flag on the workflow input.

## Workflow input

```
DailyPipelineWorkflowInput {
    target_date:        LocalDate  // trading date to process
    poll_until_ready:   boolean    // cron passes true; ad-hoc passes false
    force_reingest:     boolean    // cron passes false; ad-hoc may pass true
    run_retention_sweep: boolean   // cron passes true; ad-hoc passes false
}
```

Three independent flags, each defaulted differently by mode:

| Flag                  | Cron default | Ad-hoc default | What it controls                               |
|-----------------------|--------------|----------------|------------------------------------------------|
| `poll_until_ready`    | `true`       | `false`        | Polling-retry on `FilesNotReady`               |
| `force_reingest`      | `false`      | (caller's choice) | Drop + re-parse if date already in DB        |
| `run_retention_sweep` | `true`       | `false`        | Drop Postgres chunks older than retention floor |

Ad-hoc runs are **purely additive** by default: they ingest the requested
date, never touch existing data outside that date, and never run the
retention sweep. The only destructive action they can take is re-ingesting
the requested date, and only when `force_reingest=true`.

## What gets downloaded vs stored

Daily we download **all three feeds** (DPLS, DEEP, TOPS), validate the
triangle, and **drop all three raw files after success**. Steady-state
disk usage for raw files: 0 bytes.

Only DPLS is parsed into Postgres. DEEP and TOPS are read in-memory by the
validators and discarded. The product runs on DPLS-derived data; DEEP and
TOPS exist solely as ongoing correctness oracles.

## Dependency graph

```
[ pre-check: weekend → exit clean ]
[ pre-check: already-ingested + !force_reingest → exit clean ]

                  ┌─→ ParseAndWriteDpls ─┐
DPLS_download ───┤                       │
                  └─→                    │
                                         ├─→ CleanupFiles ─→ [if cron] RetentionSweep
                  ┌─→                    │
DEEP_download ───┼─→ ValidateTriangle ──┘
                  │
TOPS_download ───┘
```

Three concurrent download branches. As soon as DPLS download finishes,
`ParseAndWriteDpls` starts. As soon as **all three** downloads finish,
`ValidateTriangle` starts. `CleanupFiles` runs after both parse and
validation have completed.

`ParseAndWriteDpls` and `ValidateTriangle` run in parallel — they don't
depend on each other.

The `RetentionSweep` step runs only when `run_retention_sweep=true` (cron).
Ad-hoc runs end at `CleanupFiles`.

## Idempotency / force-reingest model

A pre-check at workflow start:

```
existing = SELECT status FROM pipeline_runs
           WHERE trading_date = target_date
             AND status IN ('completed', 'validation_failed_data_ok')

if existing AND NOT input.force_reingest:
    log "date already ingested, skipping (use force_reingest=true to override)"
    write pipeline_runs row with status='skipped_already_ingested'
    exit clean
```

If `force_reingest=true`, the pre-clean step in `ParseAndWriteDpls`
deletes all DPLS rows for `target_date` across every event table before
re-parsing. The new run replaces the old one.

`pipeline_runs` is the system-of-record for "have we processed this
date." `trades.feed_source='DPLS' AND ts::date=X` is the data-of-record.
In normal operation the two agree; if they diverge, the data tables are
authoritative and a row gets recorded in `pipeline_runs` reflecting that.

## Activities

| Activity | Purpose | Retry policy |
|---|---|---|
| `ResolveUrlActivity(date, feed)` | Fetch the IEX HIST listing for the date; return the download URL for the given feed. | **Polling-retry on `FilesNotReady`** when `poll_until_ready=true`: 15-min interval, 3-hr total budget. **Non-retriable** when `poll_until_ready=false`. **Transient-retry on `NetworkError`** always: 30-sec × 5. |
| `DownloadFileActivity(url, dest_path)` | HTTPS pull a .pcap.gz from GCS to local disk. Heartbeats every few seconds during the multi-GB pull. | Transient-retry on `NetworkError`, `HttpServerError`: 30-sec × 5. Non-retriable on `FileNotFoundAtUrl` (means our resolve gave a bad URL — bug) or `DiskFull`. |
| `ParseAndWriteDplsActivity(path, target_date)` | Idempotent. Pre-clean: deletes existing rows for `(feed_source='DPLS', date=target_date)` across all DPLS tables. Then parses + COPY-writes the file. Heartbeats every 100 K rows. | Transient-retry on `PostgresTransientError`: 30-sec × 3. Non-retriable on `DecodeError(strict)`. |
| `ValidateTriangleActivity(dpls_path, deep_path, tops_path, target_date)` | Runs all three validators in-memory (`DplsBboCrossValidator`, `DeepBboCrossValidator`, `DeepVsDplsValidator`). Writes one row to `validation_runs`. **Validation failure does NOT block parse or cleanup.** | Transient-retry on `PostgresTransientError`: 30-sec × 3. Non-retriable on a real validation mismatch (we WANT the failure visible). |
| `CleanupFilesActivity(paths, parse_status, validation_status)` | Deletes the .pcap.gz files for this run **only if BOTH parse and validation succeeded**. On either failure, files are kept for forensic debugging. | None — best-effort. Failure to delete a file is logged and ignored. |
| `RetentionSweepActivity(today, retain_days=30)` | Runs only in cron mode (gated by `run_retention_sweep` flag). Drops TimescaleDB chunks older than `today - retain_days` across all DPLS event tables (and the DPLS feed-source rows in shared tables). Idempotent — re-running on the same day is a no-op. | Transient-retry on `PostgresTransientError`: 30-sec × 3. Failure is logged but does NOT fail the workflow (already-completed parse/validate are real wins; retention is opportunistic). |

## Trading-day detection

The IEX HIST bare listing endpoint (`/api/1.0/hist`) returns a dict keyed
by every trading day IEX has data for. We use **presence/absence in this
dict as our calendar**.

- Date present → trading day → ingest.
- Date absent at trigger time AND `poll_until_ready=true` → could be (a) not yet
  uploaded, or (b) a non-trading day. Polling-retry over 3 hours resolves
  it: trading days reliably have files by 12:11 AM ET worst observed, so a
  3-hour budget from midnight is comfortable.
- Date absent after polling budget exhausted → non-trading day (or rare
  IEX outage on a real trading day). Workflow exits cleanly with a
  `skipped_no_data` row in `pipeline_runs`.

No hardcoded holiday list. No external calendar API. The HIST listing IS
our calendar.

Cheap weekend short-circuit before the API call: if
`target_date.dayOfWeek` is Saturday or Sunday, exit immediately without
polling. Saves 12 useless API calls 2 days a week.

## Retention

Two distinct retention concerns, both governed by the cron workflow only:

**Raw .pcap.gz files on disk.** Dropped per-pipeline-run, after parse +
validate both succeed. Steady-state usage: 0 bytes. No rolling-window
file retention — every file is processed-and-deleted on the same workflow
run that downloaded it.

**Postgres event data.** **30-day rolling window.** The scorer's
baseline needs ≥ 30 trading days; this matches that floor exactly. Each
cron workflow run's last activity is `RetentionSweepActivity`, which
drops TimescaleDB chunks where the max date is older than `today - 30 days`
across all DPLS event tables (`orders_add`, `orders_modify`,
`orders_delete`, `orders_executed`, `clear_books`, plus the rows with
`feed_source='DPLS'` in shared tables like `trades`, `trade_breaks`,
`status_events`, `retail_liquidity`, `securities`).

Ad-hoc runs **never** trigger the retention sweep (the workflow input
flag is defaulted to false). Old historical loads (e.g. the 2026-05-07
and 2026-05-08 TOPS data we hand-loaded for the initial cross-validation)
are unaffected by cron retention since their feed_source isn't DPLS.

`narratives`, `validation_runs`, and `pipeline_runs` are retained
indefinitely — small tables, valuable for retrospective analysis.

## Schedule

```
Cron: 0 0 * * 2-6
Timezone: America/New_York
Workflow: DailyPipelineWorkflow
Input: { target_date: <yesterday in ET>, poll_until_ready: true }
```

Tue–Sat fires at midnight ET. Each fire processes the previous calendar
day's data. Sun and Mon don't fire (no Saturday or Sunday data exists).
Holidays are handled at runtime via the listing-absent path.

Temporal `Schedule` is set up idempotently at `WorkerMain` startup,
following the found-footy pattern: re-running the worker re-applies the
schedule but doesn't duplicate it.

## Tables

New table for this design:

```sql
CREATE TABLE validation_runs (
    trading_date          DATE PRIMARY KEY,
    run_at                TIMESTAMPTZ NOT NULL,
    dpls_tops_match_pct   DOUBLE PRECISION,
    deep_tops_match_pct   DOUBLE PRECISION,
    dpls_deep_match_pct   DOUBLE PRECISION,   -- the load-bearing 100% leg
    trade_volume_match    BOOLEAN,             -- 9134/9134 symbols, 0 share delta?
    elapsed_seconds       INTEGER,
    notes                 JSONB
);
```

Existing `pipeline_runs` table gets new status values: `skipped_weekend`,
`skipped_no_data` (= holiday or outage), `parse_failed`, `validation_failed`.

## Activity-level error model

Three exception categories, each maps to a different retry behavior:

1. **`Transient*` exceptions** (`TransientNetworkError`,
   `PostgresTransientError`, `IOException`, `HttpServerError`) — always
   retriable, short backoff. Same idea across all activities.

2. **`FilesNotReady`** — retriable only when polling is enabled.
   Long backoff (15 min × 3 hr).

3. **Permanent failures** (`NotATradingDay`, `FileNotFoundAtUrl`,
   `DiskFull`, `DecodeError`) — non-retriable. Workflow fails with the
   activity's exception bubbling up. Some are exit-clean (NotATradingDay
   doesn't fail the workflow, just terminates it). Others fail loudly
   (DecodeError surfaces in Temporal UI as a real anomaly).

## Decisions locked in

(was "open questions" — these are now answered.)

- ✅ **Postgres retention period: 30 days.** Matches scorer's baseline floor.
- ✅ **Validation-failure semantics:** validation failure does NOT block
  parse; product data still lands. On validation failure, raw .pcap.gz
  files are KEPT for forensic debugging (manual cleanup if needed).
- ✅ **Idempotency / force-reingest:** ad-hoc runs default to skipping a
  date that's already in `pipeline_runs` as 'completed'. Pass
  `force_reingest=true` to drop and re-parse.
- ✅ **Ad-hoc retention behavior:** ad-hoc workflows NEVER run the
  retention sweep. Only cron mode does.

## Still open (to be resolved before / during coding)

1. **`add_retention_policy()` vs `RetentionSweepActivity`.** Both implement
   the 30-day Postgres retention. TimescaleDB's native policy runs in the
   background asynchronously, no Temporal visibility. The explicit activity
   runs at the end of each cron workflow, observable in Temporal UI.
   Currently leaning explicit activity — more observable, gated by the
   `run_retention_sweep` input flag (which honors ad-hoc-doesn't-touch-it).
   Confirm.

2. **Validation-runs row written even on validation failure?** If
   validation activity fails partway through, do we write a partial row
   (with the metrics we got + status='failed'), or write nothing? Default
   plan: write a row with `status='failed'` so the daily timeline shows
   the gap explicitly.

3. **Re-ingest behavior on partial-state failure.** If `force_reingest=true`
   runs the pre-clean (drops existing rows) then the parse crashes
   partway, you've LOST the previous data for that date. Acceptable risk
   for ad-hoc backfills (you can re-trigger), or do we want a transactional
   parse-to-temp-table-then-swap? My recommendation: accept the risk;
   transactional swap is significant code for an edge case.

4. **Worker container deployment.** The Temporal worker runs inside the
   existing `long-exposure-dev-worker` container. `Dockerfile.dev`
   switches its entry point from the current smoke-test `Main` to the
   new `WorkerMain`. Confirm this is the right host (probably yes — no
   reason to add a new container).

5. **Backfill workflow.** Bootstrap will eventually need 30 days of
   historical ingests for baseline computation. Options: (a) trigger 30
   ad-hoc workflows manually, (b) build a `BatchBackfillWorkflow` that
   fans out to 30 child workflows. The fan-out version is ~50 LOC and
   more observable. Worth doing — but NOT Sprint 1.

6. **Failure notifications.** Out of scope for Sprint 1; Temporal UI is
   sufficient. Revisit once the monitoring stack matures.

## Net Sprint 1 file list

1. `WorkerMain` — entry point; **applies schema via `SchemaManager.apply()`
   first** (idempotent, picks up the new `validation_runs` table); then
   registers activities + workflow; connects to Temporal; sets up
   schedules; blocks on the worker. Schema apply MUST run before
   `Worker.start()` — otherwise activities referencing new tables fail
   on the first run after a migration.
2. `DailyPipelineWorkflow` + input dataclass.
3. `ResolveUrlActivity` impl.
4. `DownloadFileActivity` impl.
5. `ParseAndWriteDplsActivity` impl (wraps existing parse loop with
   pre-clean step).
6. `ValidateTriangleActivity` impl (orchestrates the three validators).
7. `CleanupFilesActivity` impl.
8. `RetentionSweepActivity` impl.
9. Schema migration: add `validation_runs` table, update `pipeline_runs`
   status enum to include `skipped_weekend`, `skipped_no_data`,
   `skipped_already_ingested`, `parse_failed`, `validation_failed_data_ok`,
   `unverified`.
10. Schedule registration in `WorkerMain.setupSchedules()`.

Code shape: ~600–900 LOC of Java. One focused day of work.

---

## Reconciliation with `README.md` and `docs/architecture.md`

Both docs were written before any Temporal code existed and describe a
9-activity pipeline. This section explicitly maps every activity they
list to our actual design — what we're building, what we're substituting,
what we're deferring.

| README/arch.md activity   | Status in this design                                              |
|---------------------------|--------------------------------------------------------------------|
| `DownloadHistActivity`    | ✓ Sprint 1, as `ResolveUrlActivity` + `DownloadFileActivity` (split). |
| `DecompressActivity`      | ✗ Not needed. Our `PcapReader` streams from `.pcap.gz` directly via gzip input stream — no separate gunzip step. README/arch are stale on this point. |
| `ParseTopsActivity`       | ✓ Sprint 1, as `ParseAndWriteDplsActivity`. README calls it "ParseTops" because it was written before the DEEP+ pivot; we parse DPLS, not TOPS. |
| `ValidateDailyTotalsActivity` | ✗ Substituted. We use `ValidateTriangleActivity` (Sprint 1) instead — a stronger correctness check (parser-to-parser equivalence) than IEX's daily-totals comparison would be. The IEX `/stats` endpoint daily-totals validator remains a deferred backlog item; not blocking. |
| `RefreshBaselinesActivity`| ✗ Sprint 4+. The TimescaleDB continuous aggregate (`daily_volume_by_symbol`) already refreshes hourly via policy. The scorer needs current baselines, so when the scorer ships we either (a) add an explicit refresh-after-parse activity, or (b) tighten the policy interval. Decide at scorer-sprint time. |
| `ScoreEventsActivity`     | ✗ Sprint 4+ (the algorithmic core; in `DailyNarrationWorkflow`, not `DailyPipelineWorkflow`). |
| `SelectTopEventsActivity` | ✗ Sprint 4+ (same). |
| `NarrateEventsActivity`   | ✗ Sprint 4+ (decomposed in our design into `ExtractFactsActivity` + `RenderProseActivity` + `GroundingVerifyActivity` per the two-pass model in `scoring-and-narration.md`). |
| `StoreNarrativesActivity` | ✗ Sprint 4+ (in `DailyNarrationWorkflow`). |
| (not listed)              | ✓ `InvalidateCacheActivity` — README pipeline mentions it. Signals the API container to refresh. Sprint 4+ companion to narration. |
| (not listed)              | ✓ `CleanupFilesActivity` — explicit raw-file delete after success. Sprint 1. |
| (not listed)              | ✓ `RetentionSweepActivity` — explicit Postgres retention drop after success on cron runs. Sprint 1. |

**Other doc-vs-design drift to clean up later** (after Sprint 1 ships,
when we refresh the docs):

- README and architecture.md say the trigger time is **6:00 AM ET**; we
  agreed on **midnight ET**. Updated rationale: empirical SLA from the
  IEX HIST listing shows files reliably available between 22:32 and
  00:11 ET; midnight gives us aggressive parallel downloads + 3-hour
  retry budget without slipping into morning.
- README and architecture.md describe a serial activity chain
  (`Download → Decompress → Parse → Validate → ...`). Our design is
  fan-out (3 concurrent download branches, then parallel
  parse + validate). The README diagram is stale.
- `operations.md` mentions a `ReplayDay` CLI for ad-hoc replay. With
  Temporal in place, replay is just an ad-hoc workflow trigger via
  Temporal UI or a thin CLI shim that calls the Temporal client. The
  CLI doc reference needs updating.

## Unverified-data semantics (Sprint 4+ relevance)

`architecture.md` describes the conservative model: validation failure
flags the date as **unverified**, and downstream activities (scoring,
narration, publishing) refuse to act on unverified data. Adopting this:

- `ParseAndWriteDplsActivity` succeeding → `pipeline_runs.status='completed'`.
- `ValidateTriangleActivity` succeeding with match rates above some
  threshold (say, ≥ 99% DPLS↔DEEP, ≥ 95% DPLS→TOPS) → status stays
  `completed`.
- `ValidateTriangleActivity` succeeding but match rates below threshold
  → status set to `unverified`. Data is still in Postgres and queryable;
  drill-down API can serve it; but `DailyNarrationWorkflow` skips
  unverified dates by default.
- `ValidateTriangleActivity` crashing (genuine error, retries exhausted)
  → status `validation_failed_data_ok`. Data is in Postgres; downstream
  treats this the same as `unverified` (skip narration).

This is Sprint 4+ wiring; for Sprint 1, we just record the validation
result accurately. The downstream skip-on-unverified behavior lives in
`DailyNarrationWorkflow` when we build it.
