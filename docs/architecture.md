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

The orchestrator is **pure orchestration** — it calls child workflows for each phase, plus `PipelineRunRecorderActivity` for cross-cutting metadata. Every phase is independently invokable for replay / backfill / iterative development. Full layout in @temporal-design.md.

| # | Phase | Workflow | What it does | Parallelism |
|---|---|---|---|---|
| 1 | Download | `DownloadWorkflow` | Resolve 3 IEX HIST URLs + download 3 `.pcap.gz` files to `/storage/raw/` | 6 activities in parallel inside the child |
| 2 | Parse | `ParseWorkflow` | DPLS .pcap.gz → 13 hypertables via JDBC COPY | sequential within child |
| 2′ | Validate | `ValidateWorkflow` | Triangle cross-validation: DPLS book ↔ DEEP price levels (100 %); DPLS/DEEP derived BBO vs TOPS Quote Updates (99.4 %). Upserts `validation_runs`. | 3 validator legs in parallel inside the child; **runs in parallel with Parse from the orchestrator's POV** (validators read raw files, not DB) |
| 3 | Score | `ScoreWorkflow` | Materialize `order_lifecycle` (one JOIN once per day) → run 7 scorers → select top-N | sequential |
| 4 | Narrate | `NarrateWorkflow` | Two-pass LLM (`BlueprintExtractor` extract → `ProseRenderer` render with JSON-schema-enforced structured output) + pure-code `GroundingVerifier` (numbers + symbol + parenthetical-company agreement) against `llama-large.joi`; cache by `event_hash`; write `narratives` (prose + `render_structured` JSONB) | per-event fan-out with sliding-window 2-concurrent guard + dedicated narration task queue |
| 5 | Cleanup | `CleanupWorkflow` | Delete the 3 `.pcap.gz` files (only on success) + `drop_chunks` older than 30 days | sequential |

The orchestrator itself only retains direct activity calls for **cross-cutting metadata** — `PipelineRunRecorderActivity` for the idempotency short-circuit, run-start, and run-completion bookkeeping. These aren't a coherent phase; they thread through the orchestration and stay as direct activity calls.

Ancillary workflows on the same task queue:

- `RefreshSymbolsWorkflow` — weekly cron (Sun 02:00 ET, paused). Refreshes the `symbols` reference table from three sources:
  1. **NASDAQ** `nasdaqlisted.txt` + `otherlisted.txt` — exhaustive ticker universe with exchange, ETF flag, round-lot size, and SEC-filing-style `Security Name`.
  2. **SEC EDGAR** `company_tickers.json` — canonical issuer name without filing decoration; preferred over NASDAQ's `Security Name` when EDGAR has a mixed-case entry. Overlay rule preserves NASDAQ's brand-correct casing when EDGAR returns all-caps (e.g. "NVIDIA Corporation" beats "NVIDIA CORP").
  3. **IEX SecurityDirectory** — LULD tier, prev-close.

  Used by `ScoreEventsActivity` to enrich every event's breakdown JSON (company_name, listing_exchange, is_etf, round_lot, prev_close_dollars, luld_tier). `CompanyNameNormalizer` runs on the NASDAQ-sourced names at score time as a fallback that's effectively a no-op on EDGAR's clean output.
- `MaterializeWorkflow` / `SelectWorkflow` — ad-hoc replay entry points; DailyPipeline invokes the underlying activities via `ScoreWorkflow` rather than these.

A failed validation flags the day's events as `unverified` rather than publishing them. Failed parse short-circuits scoring + narration but doesn't fail the workflow (operators can re-run via `ScoreWorkflow` / `NarrateWorkflow` after fixing the underlying issue).

## Data layout

The schema (committed in `parser/src/main/resources/schema.sql`, applied idempotently by `SchemaManager` at activity start) has three layers:

**Wire-format hypertables (13)** — one per IEX message type, partitioned on the message timestamp column. Append-only. `feed_source` column on every row identifies whether the row came from DPLS, DEEP, or TOPS (so a single date can carry data from multiple feeds during the validation triangle):

| DPLS-side | TOPS/DEEP-shared |
|---|---|
| `orders_add`, `orders_modify`, `orders_delete`, `orders_executed`, `clear_books` | `trades`, `trade_breaks`, `quotes`, `status_events`, `auction_info`, `official_prices`, `securities`, `retail_liquidity` |

**Derived hypertable** — `order_lifecycle` (one row per order: paired `Add` + terminal `Delete`/`Execute` event with lifetime), built once per day by `MaterializeOrderLifecycleActivity` between Parse and Score. Partial index on `lifetime_ns < 100 ms` so `PostCancelClusterScorer` and `LayeringScorer` hit a sub-second sequential range scan instead of a 13 GB hash JOIN.

**Standard tables (5)**:
- `scored_events` — per-scorer raw output (~660 K rows/day under current thresholds). Has `subsumed_by_event_id` for children absorbed via co-occurrence enrichment.
- `selected_events` — top-N per scorer, the narration input set (90–164 rows/day).
- `narratives` — keyed by `event_hash` (which incorporates breakdown + prompt versions, so prompt changes invalidate the cache). Stores both stitched `narrative TEXT` and `render_structured JSONB` (the three-slot `{lead, facts, co_occurring}` form).
- `symbols` — per-ticker reference (company name, listing exchange, ETF flag, round lot, prev close, LULD tier).
- `pipeline_runs` — Temporal workflow lifecycle bookkeeping with status (`ok`, `unverified`, `parse_failed`, `skipped_already_ingested`, `skipped_no_data`).
- `validation_runs` — triangle cross-validation results per trading day (DPLS↔DEEP price-level match rate, DPLS→TOPS BBO match rate, DEEP→TOPS BBO match rate).

**Continuous aggregate** — `daily_volume_by_symbol` for the rolling per-symbol baselines that future inter-day scorers will use. Populated incrementally as wire-format hypertables receive new data.

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
