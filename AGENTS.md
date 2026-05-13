# Long Exposure — Agent Context

Daily IEX market-data narrative pipeline. Java parser + Temporal workflows + TimescaleDB + FastAPI, surfaced as a UI tab inside the `vedanta-systems` portal.

This file is your front door. Read it first; follow the imports below for deeper detail.

> **Naming note.** The product/spec name IEX uses for the order-by-order feed is **DEEP+**. The HIST filename token for the same feed is **DPLS** (the `+` is awkward in filenames/URLs). Docs and prose use DEEP+ as the canonical product name; code identifiers (packages, classes, enum constants, log strings) use `Dpls` / `DPLS` for symmetry with `Tops` / `Deep` — all 4-letter feed names. Same wire format, same Message Protocol ID `0x8005`.

## Run

```bash
docker compose -f docker-compose.dev.yml up -d
```

- API:         http://long-exposure-dev-api.luv/api/v1/health
- Temporal UI: http://long-exposure-dev-temporal-ui.luv
- Adminer:     http://long-exposure-dev-adminer.luv

Production: `docker compose -f docker-compose.yml up -d`.

The frontend lives in a different repo — see [Frontend integration](#frontend-integration) below.

## Stack

- **Parser / worker**: Java 21, Gradle Kotlin DSL, pcap4j, Temporal Java SDK, JDBC `COPY` into TimescaleDB.
- **Workflow orchestration**: Temporal (its own Postgres for metadata, separate from the events DB).
- **Database**: Postgres 16 + TimescaleDB extension (`timescale/timescaledb:latest-pg16`). Hypertable for events; continuous aggregates for T-30 baselines.
- **API**: FastAPI 3.12 (asyncpg + psycopg + pydantic + structlog).
- **External LLM**: `Qwen3.5-122B-A10B` 4-bit quant via llama.cpp on `joi` (Framework Desktop Strix Halo, 128 GB unified). Observed throughput **~23 tok/sec** end-of-stream, which is the relevant number for capacity planning the narration loop (5–50 narrations/day × ~150 tokens each = budget-comfortable). OpenAI-compatible HTTP; reachable as `llama-large.joi` from the worker via the `luv-prod` / `luv-dev` shared docker network. Live model IDs may shift; check `curl http://joi.<tailnet>:3101/v1/models` before relying on them.

## Where to look first

- @README.md — public-facing project description (motivation, architecture, alternatives considered, protocol notes)
- @docs/architecture.md — service layout + data flow diagram
- @docs/plan.md — sprint-by-sprint build plan with current progress (was 22-day plan; compressed substantially)
- @docs/todo.md — active checklist; **paste-ready start-of-session block at the top**
- @docs/decisions.md — append-only log of architectural decisions
- @docs/scoring-and-narration.md — design reference for the EventScorer + LLM prompt engineering (DEEP+ pattern catalog, narration grounding rules, output structure)
- @docs/protocol-notes.md — IEX-TP + TOPS + DEEP+ spec notes, parser gotchas
- @docs/operations.md — runbook (bring-up, restart, schema refresh, replay a day)
- @deploy/INFRA-NOTES.md — Caddyfile entries + vedanta-systems wiring needed outside this repo

## Frontend integration

**Long Exposure does NOT have a frontend in this repo.** The unified portal at `vedanta.systems` (sources in `~/workspace/dev/vedanta-systems/`, React + TypeScript + shadcn/ui) hosts the UI as a `long-exposure-browser` component and proxies `/api/long-exposure/*` to this project's API container via its own nginx.

Mirror this pattern for any future personal-project work: per-project repos ship an API, vedanta-systems hosts the UI. found-footy and spin-cycle follow the same shape.

## Conventions

- **Commits**: no `Co-Authored-By: Claude` trailer. Lowercase prefix style (`chore:`, `fix:`, `feat:`, `docs:`, `perf:`, `refactor:`, `test:`). Scope in parens when helpful: `feat(parser):`, `fix(api):`.
- **No host ports for HTTP services.** Everything routes via the workspace `proxy` Caddy on hostname (`long-exposure-{prod,dev}-{api,temporal-ui,adminer}.luv`). Internal data services (postgres, temporal gRPC) stay on the `long-exposure-{prod,dev}` bridge network only.
- **Tailnet identifier**: do NOT commit it to public docs or example files. `.env` is gitignored; `.env.example` is the public template.
- **Java logging**: use structured logging via the configured logback + logstash encoder (deps already in `parser/build.gradle.kts`). Avoid `System.out.println` outside `Main.java`'s startup banner.
- **Python logging**: `structlog` is configured in `api/src/long_exposure_api/main.py`. Use `log = structlog.get_logger()`, never `print()`.
- **Schema**: lives version-controlled in the repo (target: `parser/src/main/resources/schema.sql`, applied by the worker on startup or by a one-shot migration activity). Don't hand-edit hypertable schemas via Adminer.

## Things to check before doing X

- **Adding a new service, network, or hostname**: read @deploy/INFRA-NOTES.md first. Caddyfile entries live in `~/workspace/proxy/caddy/caddy.d/long-exposure.caddy`, not here. Any public-facing concern goes through `vedanta-systems`'s nginx, not a new Cloudflare tunnel.
- **Adding a new TOPS message decoder**: read @docs/protocol-notes.md first for the IEX-TP + TOPS framing. Every new decoder gets (a) a paired entry in `DailyTotalsValidator` (cross-check against IEX's daily summaries) AND (b) a reference-implementation diff against `WojciechZankowski/iextrading4j-hist` on a sample .pcap.gz. Two independent correctness checks.
- **Touching `Main.java`**: when `IEX_PCAP_FILE` is unset, `Main.main()` hands off to `WorkerMain.start()` which connects to `TEMPORAL_HOST` and registers the daily-pipeline workflows + activities on task queue `long-exposure-daily-pipeline`. Setting `IEX_PCAP_FILE` returns to smoke-test / cross-validation CLI mode. See @docs/temporal-design.md for the full workflow / activity layout (Mermaid diagrams, per-activity behavior, retry policies, the pre-clean idempotency model).
- **Touching the events schema**: the events table is a TimescaleDB hypertable. Schema migrations need to handle the hypertable conventions (`create_hypertable`, continuous aggregate refresh policies). See @docs/decisions.md for why TimescaleDB over QuestDB / DuckDB / kdb+.
- **Worker/API talking to each other**: they don't. The API is read-only against the events + narratives tables in Postgres. The worker writes via JDBC `COPY`. No direct API-to-worker calls.
- **LLM calls**: only from Temporal activities, only inside `NarrateEventsActivity`. Cache by event hash — re-running a day's pipeline must not re-narrate identical events.
- **Touching feed-handling code**: **DEEP+ (DPLS) is v1** as of the 2026-05-11 pivot (see @docs/decisions.md). TOPS code in `com.longexposure.tops.*` is **repurposed as the validation oracle**, not deleted. Admin decoders in `com.longexposure.admin.*` are shared across feeds and reused as-is. DEEP+ trading decoders go in `com.longexposure.dpls.*` (mirrors the existing `com.longexposure.tops.*` package layout). When implementing a DEEP+ decoder, cross-check it against the corresponding TOPS data already in the events DB for the same trading day.

## Reference material — local files

- **IEX spec PDFs**: `~/workspace/data/long-exposure/specs/` — all 7 (TOPS 1.66, TOPS 1.5, DEEP 1.08, DEEP+ 1.02, plus the three SNAP specs which are out of scope). Source URLs in @docs/decisions.md.
- **HIST .pcap.gz files**: `~/workspace/data/long-exposure/raw/`. Download via `https://iextrading.com/api/1.0/hist?date=YYYYMMDD` — JSON listing returns per-feed download links from Google Cloud Storage. Naming: `YYYYMMDD_IEXTP1_<FEED><VERSION>.pcap.gz` where FEED ∈ {TOPS, DEEP, DPLS}.

## Active state

- **Phase**: Temporal Sprint 1 complete. Daily pipeline workflow proven end-to-end on 2026-05-08: download → pre-clean → parse-and-write (364 M DPLS rows) → 3 parallel validators (`status=passed`, DPLS↔DEEP 99.99999 %, BBO legs 99.4017 % bug-equivalent) → cleanup (gated on cron mode) → retention sweep. Cron schedule registered paused for dev. Next sprint: bootstrap baselines (30-day backfill) + event scoring (the algorithmic core). See @docs/temporal-design.md for the workflow / activity layout.
- **Implementation status** (as of 2026-05-13):
  - Pcap-ng reader, IEX-TP decoder, 7 admin decoders + sealed `AdminMessage`, common `IexMessage` marker, shared `wire/` package.
  - **TOPS parser**: 5 trading decoders + `TopsMessageRouter`. 295 M messages loaded to Postgres in 22:27 for 2026-05-08.
  - **DPLS parser**: 7 trading decoders + `DplsMessageRouter`, order-level `OrderBook` + manager. Throughput ≈ 3.75 M msg/sec parse-only; 364 M rows written to Postgres in 35:07 (~174 K rows/sec) end-to-end.
  - **DEEP parser**: `PriceLevelUpdate` decoder + `DeepMessageRouter`, price-level `PriceLevelBook` + manager. Reuses TOPS decoders for shared T/B/X/A trading types.
  - **Cross-validation triangle**: DPLS↔DEEP (price-level), DPLS→TOPS BBO, DEEP→TOPS BBO. Results captured in @docs/validation-results.md — two consecutive days (2026-05-07 and 2026-05-08) with **100.000 % DPLS↔DEEP** (the load-bearing correctness claim) and **99.4184 % BBO match**, parsers bug-equivalent down to the share. Residual ~0.6 % is a TOPS-side spec/data gap (per-symbol round-lot tier not in any published spec); fully documented, requires external data to close further.
  - **Tests**: full suite passing (`IexTpDecoder`, admin, TOPS, DPLS, DEEP, `wire/RoundLot` + `wire/ProtectedBbo` + book state-machine tests).
  - **Storage**: 13 hypertables — 8 from TOPS-side (trades, trade_breaks, quotes, status_events, auction_info, official_prices, securities, retail_liquidity) plus 5 new DPLS-side (orders_add, orders_modify, orders_delete, orders_executed, clear_books). 2026-05-08 fully loaded for both feeds. Cross-feed trade volume validates from SQL: 9 134/9 134 symbols match, 0 share delta.
  - **Temporal pipeline**: `DailyPipelineWorkflow` + `ValidateOnlyWorkflow` on task queue `long-exposure-daily-pipeline`. 10 activities: `ResolveUrl`, `DownloadFile`, `ParseAndWriteDpls` (idempotent via pre-clean), 3 parallel validators (`DplsDeepValidate`, `DplsTopsValidate`, `DeepTopsValidate`), `RecordValidation`, `CleanupFiles` (gated on cron mode), `RetentionSweep`, `PipelineRunRecorder`. Cron schedule `0 0 * * 2-6 America/New_York` registered paused. Two new DB tables: `pipeline_runs` + `validation_runs`. Full layout in @docs/temporal-design.md.
- **Bring-up status**: dev stack running on luv; prod stack on luv compose not yet started, Caddyfile entries for `*.luv` applied last week.
- **Data on disk** (`~/workspace/data/long-exposure/`):
  - `raw/` — 2026-05-07 + 2026-05-08 .pcap.gz for all three feeds (TOPS / DEEP / DPLS, ≈ 10–14 GB each).
  - `specs/` — all 7 IEX wire-protocol spec PDFs plus the IEX Auction Process spec (added 2026-05-12 during residual investigation; confirms IEX auctions only apply to IEX-listed securities, so they don't drive our non-IEX-listed mismatch cluster).
  - `prior_close_20260507.csv` — per-symbol last-trade-on-IEX from 2026-05-07 TOPS, used as a per-symbol round-lot proxy for the 2026-05-08 BBO validation.
- **Frontend cleanup (2026-05-10)**: removed Svelte SPA scaffold; vedanta-systems is the unified portal. See @docs/decisions.md.

## Memory model (for me, the agent)

This project uses **`AGENTS.md` (this file) as the canonical agent context**, not auto-memory. Auto-memory at `~/.claude/projects/-home-vedanta-workspace-dev-long-exposure/memory/` holds only **user-scoped preferences and feedback** (e.g., the "no per-project frontend" rule); project facts live here in the repo so they're version-controlled and visible to all tools.
