# Long Exposure — Agent Context

Daily IEX market-data narrative pipeline. Java parser + Temporal workflows + TimescaleDB + FastAPI, surfaced as a UI tab inside the `vedanta-systems` portal.

This file is your front door. Read it first; follow the imports below for deeper detail.

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
- **External LLM**: `Qwen3.5-122B-A10B` (chat) served from `joi` over Tailscale, OpenAI-compatible endpoint. Reachable as `llama-large.joi` from the worker container via the `luv-prod` / `luv-dev` shared docker network. Live model IDs may shift; check `curl http://joi.<tailnet>:3101/v1/models` before relying on them.

## Where to look first

- @README.md — public-facing project description (motivation, architecture, alternatives considered, protocol notes)
- @docs/architecture.md — service layout + data flow diagram
- @docs/plan.md — the 22-day build plan, day-by-day, with current progress
- @docs/todo.md — active checklist; this is the agent-editable scratchpad
- @docs/decisions.md — append-only log of architectural decisions
- @docs/protocol-notes.md — IEX-TP + TOPS spec notes, parser gotchas
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
- **Touching `Main.java`**: it's currently a Day-1 stub that prints env vars and blocks. Once the Temporal worker registration lands, this becomes the worker entry point — connect to `TEMPORAL_HOST`, register activity classes, block on the worker.
- **Touching the events schema**: the events table is a TimescaleDB hypertable. Schema migrations need to handle the hypertable conventions (`create_hypertable`, continuous aggregate refresh policies). See @docs/decisions.md for why TimescaleDB over QuestDB / DuckDB / kdb+.
- **Worker/API talking to each other**: they don't. The API is read-only against the events + narratives tables in Postgres. The worker writes via JDBC `COPY`. No direct API-to-worker calls.
- **LLM calls**: only from Temporal activities, only inside `NarrateEventsActivity`. Cache by event hash — re-running a day's pipeline must not re-narrate identical events.
- **Touching feed-handling code**: **DEEP+ (DPLS) is v1** as of the 2026-05-11 pivot (see @docs/decisions.md). TOPS code in `com.longexposure.tops.*` is **repurposed as the validation oracle**, not deleted. Admin decoders in `com.longexposure.admin.*` are shared across feeds and reused as-is. DEEP+ trading decoders go in `com.longexposure.deepplus.*` (mirrors the existing `com.longexposure.tops.*` package layout). When implementing a DEEP+ decoder, cross-check it against the corresponding TOPS data already in the events DB for the same trading day.

## Reference material — local files

- **IEX spec PDFs**: `~/workspace/data/long-exposure/specs/` — all 7 (TOPS 1.66, TOPS 1.5, DEEP 1.08, DEEP+ 1.02, plus the three SNAP specs which are out of scope). Source URLs in @docs/decisions.md.
- **HIST .pcap.gz files**: `~/workspace/data/long-exposure/raw/`. Download via `https://iextrading.com/api/1.0/hist?date=YYYYMMDD` — JSON listing returns per-feed download links from Google Cloud Storage. Naming: `YYYYMMDD_IEXTP1_<FEED><VERSION>.pcap.gz` where FEED ∈ {TOPS, DEEP, DPLS}.

## Active state

- **Phase**: post-TOPS-parser, **pivoting to DEEP+ as v1** (decision recorded 2026-05-11 in @docs/decisions.md). TOPS work stays as the validation oracle. Next session starts on the DEEP+ parser; see @docs/todo.md for the paste-ready startup checklist.
- **Implementation status** (as of 2026-05-11):
  - Parser stack done end-to-end for TOPS: pcap-ng reader, IEX-TP decoder, 7 admin decoders + sealed `AdminMessage`, 5 TOPS trading decoders + `TopsMessageRouter`, 48 passing JUnit tests
  - Storage: `schema.sql` with 8 hypertables + continuous aggregate; `SchemaManager` + `TimescaleWriter` with COPY-based bulk insert
  - End-to-end verified: 9.5 GB 2026-05-08 TOPS HIST → 294,790,405 messages → Postgres in 22:27 min
  - Continuous aggregate `daily_volume_by_symbol` populated for 2026-05-08 (9,134 symbols, 770M shares, 7.75M trades)
- **Bring-up status**: dev stack running on luv; prod stack on luv compose not yet started, Caddyfile entries for `*.luv` already applied earlier this week.
- **DEEP+ status**: spec PDF at `~/workspace/data/long-exposure/specs/deep-plus-1.02.pdf` fully read. No data downloaded yet. No code written yet — that's tomorrow's first sprint.
- **Frontend cleanup (2026-05-10)**: Removed an erroneous Svelte SPA scaffold; vedanta-systems is the unified portal. See @docs/decisions.md.

## Memory model (for me, the agent)

This project uses **`AGENTS.md` (this file) as the canonical agent context**, not auto-memory. Auto-memory at `~/.claude/projects/-home-vedanta-workspace-dev-long-exposure/memory/` holds only **user-scoped preferences and feedback** (e.g., the "no per-project frontend" rule); project facts live here in the repo so they're version-controlled and visible to all tools.
