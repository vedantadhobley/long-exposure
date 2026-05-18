# Architecture

High-level design and data flow. Companion to @../README.md (which is the public-facing project description) — this doc is the operational view.

## Service layout

```
                                       ┌──────────────────────────────────┐
                                       │   vedanta-systems (separate repo)│
                                       │   React + TS + shadcn/ui + nginx │
                                       │                                  │
                                       │   /api/long-exposure/*  ─────┐   │
                                       └──────────────────────────────│───┘
                                                  cloudflared          │
                                                       │               │ (proxy net)
                                                       ▼               ▼
                                       ┌─────────────────────────────────────┐
                                       │   workspace `proxy` Caddy (luv)     │
                                       │   hostname routing on the tailnet   │
                                       └──────────────────┬──────────────────┘
                                                          │
        ┌─────────────────────────────────────────────────┼─────────────────────────────┐
        │                                                 │                             │
        ▼                                                 ▼                             ▼
┌────────────────────┐                      ┌────────────────────────┐       ┌─────────────────────┐
│ long-exposure-prod │                      │ long-exposure-prod     │       │ long-exposure-prod  │
│ -api  (FastAPI)    │                      │ -temporal-ui           │       │ -adminer            │
│ :3001              │                      │ :8080                  │       │ :8080               │
└─────────┬──────────┘                      └────────────┬───────────┘       └──────────┬──────────┘
          │                                              │                              │
          │ async pg                                     │ TEMPORAL_ADDRESS             │ ADMINER_DEFAULT_SERVER
          ▼                                              ▼                              ▼
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                          long-exposure-prod (internal docker network)                            │
│                                                                                                   │
│  ┌──────────────────────┐    ┌──────────────────────┐    ┌────────────────────────────────────┐ │
│  │ postgres (TimescaleDB│    │ temporal-postgres    │    │ temporal (auto-setup)              │ │
│  │ pg16) :5432          │    │ pg16-alpine :5432    │    │ :7233  ─── gRPC                    │ │
│  │ events + narratives  │    │ temporal metadata    │    └────────────────┬───────────────────┘ │
│  └─────────▲────────────┘    └──────────────────────┘                     │                     │
│            │                                                              │                     │
│            │ JDBC COPY                                                    │ Temporal SDK        │
│            │                                                              │                     │
│  ┌─────────┴──────────────────────────────────────────────────────────────┴───────────────────┐ │
│  │  worker  (Java 21 + pcap4j + temporal-sdk)                                                  │ │
│  │  hosts every Temporal activity: download, parse, validate, score, narrate, store            │ │
│  └─────────────────────────────────────────────────────────────────────────────────────────────┘ │
│                                                                            │                     │
└────────────────────────────────────────────────────────────────────────────│─────────────────────┘
                                                                             │
                                                                  via `luv-prod` net
                                                                             ▼
                                                              ┌──────────────────────────┐
                                                              │ joi (separate node)      │
                                                              │ llama-large.joi          │
                                                              │ Qwen3.5-122B-A10B (chat) │
                                                              └──────────────────────────┘
```

The dev stack is identical with `-dev` suffixes and bind-mounted source.

## Networks

- `long-exposure-{prod,dev}` — internal bridge for postgres ↔ worker ↔ temporal traffic.
- `proxy` — workspace-shared external network. Joined by `temporal-ui`, `adminer`. Caddy reaches them by container name.
- `luv-{prod,dev}` — workspace-shared external network. The worker joins this to reach `llama-large.joi` via tailnet. **Postgres also joins** this so vedanta-systems' unified Express API (which lives on the same network) can query the narratives table directly. There is NO HTTP API container in this repo.

## Pipeline (Temporal workflow)

`DailyPipelineWorkflow` runs nightly at 00:00 America/New_York (Tue–Sat, schedule paused by default during dev). T+1 HIST data is usually available between 22:32 ET and 00:11 ET; midnight gives the workflow a 3-hour retry budget on the upstream-resolve step without spilling into the morning.

Activities are listed in execution order. Steps 1 and 2 fan out into 3 parallel branches (one per IEX feed: DPLS / DEEP / TOPS). The 3 validators after parsing run concurrently. The full activity catalog + retry policies live in @temporal-design.md.

| # | Activity | Purpose | Long-running? |
|---|---|---|---|
| 1 | `ResolveUrlActivity` ×3 | Hit `iextrading.com/api/1.0/hist?date=…`, pick GCS download URLs for each of the three feeds | no |
| 2 | `DownloadFileActivity` ×3 | HTTPS stream each `.pcap.gz` to `/storage/raw/` (resumable, short-circuits on existing file) | yes (heartbeat) |
| 3 | `ParseAndWriteDplsActivity` | Java parser → 13 hypertables via JDBC `COPY` (DPLS feed only; idempotent pre-clean) | yes (heartbeat) |
| 4 | `DplsDeepValidatorActivity` + `DplsTopsValidatorActivity` + `DeepTopsValidatorActivity` (parallel) | Triangle cross-validation: DPLS book ↔ DEEP price levels (100 %); DPLS / DEEP derived BBO vs TOPS Quote Updates (99.4 %) | yes |
| 5 | `RecordValidationActivity` | Upsert the three validator results into `validation_runs`, classify run status | no |
| 6 | `MaterializeOrderLifecycleActivity` | Pair Add ↔ Delete + last Execute per order_id into the `order_lifecycle` hypertable (one expensive JOIN amortized across all downstream scorers) | yes |
| 7 | `ScoreEventsActivity` | Run the 7-scorer registry against parsed + materialized data, write to `scored_events` | yes |
| 8 | `SelectTopEventsActivity` | Per-scorer top-N pull from `scored_events` into `selected_events` | no |
| 9 | `NarrateEventsActivity` | Two-pass extract → render → pure-code grounding verify against `llama-large.joi`; cache by `event_hash`; write `narratives` | yes (heartbeat) |
| 10 | `CleanupFilesActivity` | Delete the 3 `.pcap.gz` files (only when `runRetentionSweep=true AND status='passed'`) | no |
| 11 | `RetentionSweepActivity` | `drop_chunks` older than 30 days from DPLS hypertables; per-row `DELETE` on shared TOPS-validation tables | no |
| 12 | `PipelineRunRecorderActivity` (start/end) | Tag the run in `pipeline_runs` for replayability and the idempotency short-circuit | no |

Two ancillary workflows on the same task queue:

- `RefreshSymbolsWorkflow` — weekly cron (Sun 02:00 ET, paused). Refreshes the `symbols` reference table from NASDAQ public listings + local IEX SecurityDirectory data. Read by `ScoreEventsActivity` to enrich event breakdown JSONs (company name, ETF flag, round lot, prev close) so the LLM narrator has structured per-ticker context.

A failed validation flags the day's events as `unverified` rather than publishing them. Failed parse fails the workflow (no scoring/narration runs against incomplete data).

## Data layout

- `events` hypertable, partitioned on the trade-time column. Append-only.
- `narratives` table keyed by event hash. Cache layer for LLM output.
- Continuous aggregates over `events` providing the rolling T-30 baselines per (symbol, metric).
- No mutations after write.

Schema lives in `parser/src/main/resources/schema.sql` (target — not yet committed; tracked in @todo.md).

## Frontend integration

Neither the API nor the UI lives in this repo — both are owned by `~/workspace/dev/vedanta-systems/`:

- `src/server/routes/long-exposure.ts` — Express router mounted at `/api/long-exposure/*`. Connects to `long-exposure-{dev,prod}-postgres:5432` over the `luv-{dev,prod}` docker network. Read-only; the worker is the only write path.
- `src/server/index.ts` — wires the router into the unified API (`vedanta-systems-{dev,prod}-api`).
- `src/components/long-exposure-browser.tsx` — the per-project browser component.
- `src/types/long-exposure.ts` — response shapes the frontend consumes.
- Project entry registered in `src/App.tsx` under `~/workspace/long-exposure` (folder listing + routing branch).

Same shape as the existing `found-footy-browser` and `spin-cycle-browser` integrations, except long-exposure is read-only — there's no inbound submission surface in this repo (the Temporal worker autonomously ingests).

## Observability

- Logs → `monitor-loki.luv` automatically via Promtail (every container's stdout).
- Container metrics → `monitor-prometheus.luv` automatically via cAdvisor.
- Temporal workflow UI is the source of truth for pipeline-execution debugging.

No per-service instrumentation needed for v1.
