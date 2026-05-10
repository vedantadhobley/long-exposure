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

- `long-exposure-{prod,dev}` — internal bridge for postgres ↔ worker ↔ temporal traffic. Not joined to anything else.
- `proxy` — workspace-shared external network. Joined by `api`, `temporal-ui`, `adminer`. Caddy reaches them by container name. `vedanta-systems`'s nginx also lives on this network and reaches `long-exposure-prod-api` over it.
- `luv-{prod,dev}` — workspace-shared external network for tailnet egress (so `worker` can resolve `llama-large.joi`).

## Pipeline (Temporal workflow)

Runs nightly at 6:00 AM ET, after T+1 HIST data becomes available. Activities listed in execution order:

| # | Activity | Purpose | Long-running? |
|---|---|---|---|
| 1 | `DownloadHistActivity` | HTTPS download from `iextrading.com/api/1.0/hist` | yes (heartbeat) |
| 2 | `DecompressActivity` | gunzip the .pcap.gz | yes (heartbeat) |
| 3 | `ParseTopsActivity` | Java parser → events hypertable via JDBC `COPY` | yes (heartbeat) |
| 4 | `ValidateDailyTotalsActivity` | cross-check parsed totals vs IEX's daily summaries | no |
| 5 | `RefreshBaselinesActivity` | refresh TimescaleDB continuous aggregates for T-30 windows | no |
| 6 | `ScoreEventsActivity` | apply `EventScorer` to every parsed event | no |
| 7 | `SelectTopEventsActivity` | top N events by score | no |
| 8 | `NarrateEventsActivity` | call `llama-large.joi`; cache by event hash | yes (heartbeat) |
| 9 | `StoreNarrativesActivity` | write to `narratives` table | no |

Failed validation flags the day's events as "unverified" rather than publishing them.

## Data layout

- `events` hypertable, partitioned on the trade-time column. Append-only.
- `narratives` table keyed by event hash. Cache layer for LLM output.
- Continuous aggregates over `events` providing the rolling T-30 baselines per (symbol, metric).
- No mutations after write.

Schema lives in `parser/src/main/resources/schema.sql` (target — not yet committed; tracked in @todo.md).

## Frontend integration

The UI is *not* in this repo. See `~/workspace/dev/vedanta-systems/`:

- `nginx.conf` — `location /api/long-exposure/ { proxy_pass http://long-exposure-prod-api:3001/; }`
- `src/components/long-exposure-browser.tsx` — the per-project browser component
- `src/contexts/LongExposureContext.tsx` (only if narratives need streaming; probably not — daily updates)
- Project entry registered in `src/App.tsx` under `~/workspace/long-exposure`

Same shape as the existing `found-footy-browser` and `spin-cycle-browser` integrations.

## Observability

- Logs → `monitor-loki.luv` automatically via Promtail (every container's stdout).
- Container metrics → `monitor-prometheus.luv` automatically via cAdvisor.
- Temporal workflow UI is the source of truth for pipeline-execution debugging.

No per-service instrumentation needed for v1.
