# Long Exposure

**A full day of IEX market data, rendered into something you can actually read.**

IEX Exchange publishes every quote, trade, and halt from every trading day as free historical data. The data moves at nanosecond speed — machine-readable, binary, inaccessible to anyone without the tooling to parse it. Long Exposure takes that data and does what a long exposure photograph does: slows everything down, captures the full arc of the day, and produces something a human can actually look at.

The name is an inversion of IEX's own identity. Flash Boys. The speed bump. Microsecond precision. Long Exposure is the opposite — deliberate, slow, readable.

---

## What it does

Every trading day, IEX publishes the previous day's full market data as a free HIST download in pcap format. Long Exposure consumes that data nightly, processes it through a multi-stage pipeline, identifies the most significant events, and generates human-readable narratives powered by a local LLM. The result is a daily market intelligence feed, served publicly and updated every morning.

A user visiting the site sees something like:

```
May 9, 2026

10:23 AM   AAPL halted for 4 minutes
"Apple experienced a regulatory halt coinciding with unusual options activity.
Volume in the preceding 10 minutes was 2.3× the daily average before normalizing
post-halt."

11:45 AM   Liquidity withdrawal in SPY
"The S&P 500 ETF saw bid-ask spreads widen to 3× normal levels for approximately
8 minutes, suggesting a temporary withdrawal of market maker liquidity."

2:15 PM    TSLA late-day volume surge
"Tesla saw a 4× volume spike in the final hour of trading with price appreciation
of 1.2%, consistent with momentum accumulation ahead of close."

[ View all events from today → ]
```

Top level: the 5–10 most significant events, AI-narrated, in plain English.
Drill down: the full parsed event log, filterable by ticker, event type, and time range.

---

## Why this is interesting

IEX publishes their market data for free as a transparency commitment. Almost nobody outside of financial firms actually reads it — the data is binary, the protocol is complex, and there is no human-readable layer on top of it.

Long Exposure creates that layer. The mission — technology making financial markets more transparent and accessible — is exactly what IEX was built around.

It also demonstrates, in a single project:

- Deep understanding of IEX's binary protocol stack
- High-performance Java systems engineering
- Time-series data infrastructure (Postgres + TimescaleDB)
- Local LLM integration for structured-data narration
- Production-grade pipeline orchestration with Temporal
- A real public product with real users

It's open source from day one — MIT licensed — positioned as a reference implementation of the IEX TOPS parser in Java with a public daily-narrative demo on top.

---

## Architecture

### Service layout

Every service runs in Docker. No host ports published for HTTP services — the workspace's shared `proxy` (Caddy) handles routing by hostname on the tailnet. Logs auto-flow to Loki via Promtail, container metrics via cAdvisor — no per-service instrumentation needed for v1.

Long Exposure does not host its own frontend. The UI surfaces inside the unified portal at `vedanta.systems` (served by `~/workspace/dev/vedanta-systems/`), which proxies `/api/long-exposure/*` to this project's API container over the shared `proxy` docker network. See [Frontend integration](#frontend-integration) below.

| Service | Container | URL (tailnet) | Internal port | Node |
|---|---|---|---|---|
| API (FastAPI) | `long-exposure-prod-api` | `long-exposure-prod-api.luv` *(also reachable as `vedanta.systems/api/long-exposure/*` via vedanta-systems' nginx)* | 3001 | luv |
| Worker (Java + Temporal SDK) | `long-exposure-prod-worker` | — (no HTTP) | — | luv |
| Temporal server | `long-exposure-prod-temporal` | — (gRPC only, internal) | 7233 | luv |
| Temporal UI | `long-exposure-prod-temporal-ui` | `long-exposure-prod-temporal-ui.luv` | 8080 | luv |
| Postgres + TimescaleDB | `long-exposure-prod-postgres` | — (PG wire only, internal) | 5432 | luv |
| Adminer (Postgres web UI) | `long-exposure-prod-adminer` | `long-exposure-prod-adminer.luv` | 8080 | luv |
| LLM (chat) | `llama-large` (existing) | `llama-large.joi` (already running) | 8080 | joi |

### Why Postgres + TimescaleDB

Postgres 16 with the TimescaleDB extension. Hypertables for the events table, partitioned on the trade-time column. Continuous aggregates for the rolling T-30 baselines so they don't have to be recomputed from raw events on every pipeline run. Standard `COPY` for bulk ingest from the Java parser via JDBC.

This is the pragmatic choice over a pure time-series DB:

- **Mature operational story.** `pg_dump` for logical backups, WAL archiving for point-in-time recovery, `pg_basebackup` for filesystem-level snapshots. Every backup tool, every monitoring integration, every blog post on schema migrations works without translation.
- **Ecosystem.** Drivers, ORMs, GUI tools, error messages with thousands of Stack Overflow answers. Friction is near zero.
- **Familiar mental model.** Debugging time stays in the project, not in learning a new query engine.
- **Future flexibility.** If Long Exposure grows orthogonal features (user accounts, saved searches, full-text search on narratives), Postgres absorbs them without adding a second database.

What we give up vs a purpose-built TSDB: ~10–20% raw scan throughput on time-range queries, and roughly 2–5× slower bulk ingest. Both are completely irrelevant at our data scale (a few hundred MB/day, ingested once nightly, served to a personal-site-scale read load).

The full alternatives analysis is in [Alternatives considered (database)](#alternatives-considered-database) below so the rationale survives future "why not X" questions.

### Why Temporal

The nightly pipeline has real failure modes at every stage — the HIST download might fail, the parser might crash on a malformed packet, the LLM might time out. Temporal provides durable workflow execution with automatic retries, activity-level fault isolation, and a full execution history. Each pipeline stage is a Temporal activity. Long-running activities (the parse, the LLM narration loop) heartbeat back to the server so transient delays don't trigger spurious retries.

### Why local LLM

Qwen3.5 122B runs on `llama-large.joi` (existing infrastructure). The honest justification is data sovereignty + zero-marginal-cost iteration on prompts, plus the homelab-systems showcase. (Cost savings are rounding error at 5–50 narrations per day.) The 122B model produces materially better financial prose than smaller models — that part *does* matter for the user-facing output.

---

## Alternatives considered (database)

This was the first major design choice and worth documenting in detail — the data shape (nanosecond timestamps, append-only event stream, time-range read pattern) made it tempting to pick something domain-specific over Postgres.

### What the workload actually looks like

1. **Ingest**: bursty bulk write once per day (one trading day's worth of events from the Java parser). Idle the rest of the time.
2. **Time-range query**: most reads are "events for symbol X between time A and time B" plus rolling-window aggregates for baselines.
3. **Public API serving**: low-concurrency reads (~tens of concurrent users at most for a personal site).
4. **No mutations after write.** Append-only.
5. **No transactional cross-table concerns.** Events are independent of metadata.

### Options evaluated

| Option | Domain fit | Operational maturity | Ecosystem | IEX signal value | Verdict |
|---|---|---|---|---|---|
| **Postgres + TimescaleDB** | good (hypertables + continuous aggregates) | high | huge | medium | **Chosen.** Best risk/return for the actual workload. |
| **QuestDB** | excellent — built for financial time-series | medium | smaller, growing | high — finance-specific user base | Strong second; rejected on operational maturity (see below). |
| **DuckDB** | excellent for analytics | n/a (embedded, no server) | growing | medium — surprising/creative | Wrong architectural shape for serving an API; great as dev-time companion. |
| **ClickHouse** | very good — columnar OLAP at scale | high | large | medium — common in adtech, less in finance | Overkill for our scale; loses finance-specific signal vs QuestDB. |
| **InfluxDB** | medium — optimized for metrics, less for rich-attribute events | high | large | low | Wrong shape; query-language churn between v2/v3 is its own thing. |
| **VictoriaMetrics** | low — pure metrics, label-based | high | medium | low | Wrong data model. |
| **kdb+ / q** | maximum — the gold standard at quant funds and exchanges | high (in finance ops) | small but deep | maximum | Rejected: q language is brutal to learn under deadline; license restrictive. Worth saying "I considered it" in interviews. |

### Why not QuestDB specifically

QuestDB was the leading alternative and the case for it is real:

**For QuestDB:**
- Built specifically for financial time-series data. User base is heavily quant-finance — strong signal value at IEX.
- Nanosecond timestamps native; columnar storage; ILP ingest at millions of rows/sec.
- Single-binary deployment, both PG-wire and HTTP-SQL interfaces.

**Why we didn't pick it:**
- **Smaller ecosystem.** Edge-case debugging means reading their Slack and GitHub issues rather than Stack Overflow. Estimate: 1–2 days of total friction over the 22-day project.
- **Backup story is filesystem-level only.** No `pg_dump`-equivalent for logical exports; you copy the partition directories. Manageable but adds an item to the disaster-recovery runbook.
- **Limited SQL feature set.** No triggers, no stored procedures, weak JSON ops, recently-added CHECK constraints. None of these matter for *this* workload but they constrain pivots if the project grows orthogonal features.
- **Schema migrations harder than Postgres.** Column type changes / renames are limited. Forces "design the schema once, well" rather than evolve it.
- **Smaller user base = fewer eyes on edge-case bugs.** Reports of issues around DST transitions, very-high-cardinality symbol columns, severely out-of-order ingestion. Mitigated by the validation harness (which we want anyway), but the failure modes are less well-mapped.
- **JOIN performance optimized for ASOF / LATEST ON, not multi-way joins on non-time columns.** Fine for our query shape, but constrains future features.

For a production system at scale, QuestDB's ~10–20% perf advantage on time-range scans probably pays for the ecosystem cost. For a 22-day solo project where the data scale is small and ecosystem friction directly translates to slipped days, Postgres+Timescale is the better risk/return.

### Why not DuckDB

DuckDB is genuinely interesting and almost the right answer for an adjacent project:

**For DuckDB:**
- Embedded — no server, the DB is a file on disk. Zero ops.
- Phenomenal SQL execution engine. Columnar. Reads parquet directly. Fast.
- "I shipped a multi-service product with no DB server" is a notable design choice.

**Why we didn't pick it:**
- **Single-writer concurrency model.** Long Exposure has one writer (the worker) but a continuously-running reader (the API). DuckDB allows one writer-mode connection at a time; the API would need a separate read-only-mode connection on the same file, with awkward coordination during writes.
- **No wire protocol.** Every consumer process needs its own DuckDB driver opening the file directly. Doesn't fit a multi-service Docker architecture cleanly — there's no DB *server* to talk to.
- **Designed for batch analytics, not API serving.** Using it against its grain.

**Where DuckDB would be the right call:** a single-process analysis tool, a notebook environment for exploring HIST data, or a parquet-based data lake pattern. The doc mentions optionally writing parquet from the parser as a future enhancement — that's where DuckDB earns a place in the project, as the dev-time companion.

### Why not kdb+

The honest answer for a longer project would be kdb+. It's what major exchanges and quant funds run. The signal value at IEX is unmatched.

**Why we didn't pick it:**
- The q language requires substantial dedicated learning time. Days 1–7 would be q tutorials, not Long Exposure.
- The free license is 32-bit-limited; production-grade use requires expensive licensing.
- The deployment model is non-standard for a Docker-shaped homelab.

**This is worth saying out loud at IEX:** "I considered kdb+ but skipped it for licensing + q learning curve in a 22-day window." Demonstrates awareness without overcommitting.

### Decision

Postgres 16 + TimescaleDB. Standard JDBC for ingest from the Java parser. Continuous aggregates for the T-30 baselines. Adminer for ad-hoc SQL queries. The ~2× ingest gap and ~15% time-range scan gap vs QuestDB are dominated by the ecosystem, backup, and migration advantages at our scale.

---

## Data feed selection — TOPS vs DEEP

IEX publishes two historical data feeds via HIST: TOPS and DEEP. Long Exposure uses TOPS only.

**TOPS — top of book.** Best bid, best ask, aggregated sizes at the top of the book, last trade information. Contains all the event types Long Exposure needs: halts, trade reports, quote updates, trading status changes, system events. A single trading day is typically a few hundred MB compressed.

**DEEP — depth of book.** Every price level with aggregated resting order sizes. Significantly larger than TOPS and contains information Long Exposure doesn't need. The full DEEP dataset across all available history exceeds 13 TB.

**DPLS (DEEP+) — order-by-order.** The most granular feed IEX publishes. ~10 GB compressed per day. Not relevant for this project.

**Decision: TOPS for v1. DEEP as a potential phase-2 if deeper liquidity analysis is added.**

---

## Protocol stack

```
.pcap.gz (compressed historical file)
  └─ pcap file (standard network capture format)
       └─ UDP packets
            └─ IEX Transport Protocol header (40 bytes)
                 └─ TOPS message block
                      └─ Individual TOPS messages
```

### IEX Transport Protocol (IEX-TP)

Every UDP packet payload begins with a 40-byte IEX-TP header:

| Field | Size | Description |
|---|---|---|
| Version | 1 byte | Protocol version |
| Reserved | 1 byte | |
| Message Protocol ID | 2 bytes | Identifies TOPS, DEEP, etc. |
| Channel ID | 4 bytes | Data channel identifier |
| Session ID | 4 bytes | Session identifier |
| Payload Length | 2 bytes | Length of message block |
| Message Count | 2 bytes | Number of messages in block |
| Stream Offset | 8 bytes | Byte offset in stream |
| First Sequence Number | 8 bytes | Sequence number of first message |
| Send Time | 8 bytes | Nanoseconds since Unix epoch |

### TOPS message types

After the IEX-TP header, each message has a 2-byte length prefix and a 1-byte message type identifier:

| Type | Character | Description |
|---|---|---|
| System Event | S | Market open, close, halt |
| Security Directory | D | Symbol metadata |
| Trading Status | H | Halted, trading, paused |
| Quote Update | Q | Best bid/ask with sizes |
| Trade Report | T | Executed trade price and size |
| Trade Break | B | Cancelled trade |
| Official Price | X | Opening/closing price |

### Price encoding

All prices are stored as 8-byte signed integers with 4 implied decimal places. `$103.25` is stored as `1032500`. Decode with `price / 10_000.0`. All fields are little-endian. In Java: `ByteBuffer.order(ByteOrder.LITTLE_ENDIAN)`.

---

## Java parser — module structure

```
long-exposure-parser/
├── build.gradle.kts
├── settings.gradle.kts
└── src/
    └── main/java/com/longexposure/
        ├── Main.java
        ├── pcap/
        │   └── PcapReader.java         (uses pcap4j)
        ├── transport/
        │   └── IexTpDecoder.java
        ├── tops/
        │   ├── TopsMessageRouter.java
        │   └── messages/
        │       ├── TopsMessage.java
        │       ├── QuoteUpdate.java
        │       ├── TradeReport.java
        │       ├── TradingStatus.java
        │       ├── SecurityDirectory.java
        │       └── SystemEvent.java
        ├── scoring/
        │   └── EventScorer.java
        ├── validation/
        │   └── DailyTotalsValidator.java   (cross-checks against IEX's published per-symbol summaries)
        └── storage/
            └── TimescaleWriter.java        (JDBC + COPY; bulk insert into hypertable)
```

### Build tooling — Gradle Kotlin DSL

Gradle with `build.gradle.kts`. Multi-stage Dockerfile (`gradle:jdk21-alpine` builder → `eclipse-temurin:21-jre-alpine` runtime) so the parser image is reproducible and the dev environment doesn't need a host-side JDK install.

---

## Validation harness

**Critical, not optional.** Pcap parsing has edge cases — truncated packets, sequence number gaps, gap-fill packets, sessions that span multiple files. Without a validator, the project ships parser bugs that get caught the first time an IEX engineer reads the output.

`DailyTotalsValidator` cross-checks:

- Total trade count per symbol against IEX's published daily volume summaries
- Total share volume per symbol
- High / low / open / close prices

These come from the same HIST feed (or IEX's free `/stats` endpoints) and are authoritative. Any discrepancy > 0.1% on a sampled symbol is a parser bug to fix before that day's events get scored.

The validator runs as the final activity in the Temporal pipeline. A failed validation flags the day's events as "unverified" rather than publishing them.

---

## Event significance scoring

Not every TOPS event is interesting. The EventScorer assigns a significance score based on:

- **Event type** — halts > spread anomalies > volume spikes > routine quotes
- **Ticker liquidity tier** — S&P 500 constituents weighted higher
- **Deviation from baseline** — how far from the ticker's own recent average (see Baseline Data below)
- **Time of day** — open and close carry more weight
- **Duration** — sustained anomalies score higher than brief ones

Each event's score is *transparent* — a reader can drill into any narrated event and see the score breakdown. "Why is this event listed?" should always have a clear, principled answer. This matters more than slick UI.

The top N events by score are selected for LLM narration. Everything is stored in Postgres for drill-down access. Continuous aggregates (TimescaleDB feature) maintain the rolling T-30 baselines incrementally so the scorer never re-computes them from raw events.

### Baseline data

"Volume was 2.3× daily average" requires storing daily averages. The pipeline depends on a rolling T-30 → T-1 baseline per symbol per metric. This is a load-bearing dependency:

- **Bootstrap**: on day-1 launch, run the pipeline against the *last 30 trading days* of HIST data. This populates the baseline before the first narrated day. IEX publishes ~3 years of historical TOPS, so backfill cost is one-time.
- **Maintenance**: each nightly run updates the rolling baseline with the previous day's data.

Without bootstrap, the first 30 narrated days will have no meaningful baselines and every event will look unusual.

---

## LLM narration pipeline

Significant events are serialized to structured JSON and sent to `llama-large.joi` (Qwen3.5 122B via llama.cpp's HTTP server) on the homelab's `joi` node:

```json
{
  "event_type": "trading_halt",
  "ticker": "AAPL",
  "timestamp": "10:23:14.847291000",
  "halt_reason": "regulatory",
  "duration_seconds": 243,
  "pre_halt_volume_ratio": 2.3,
  "pre_halt_price_change_pct": 0.8
}
```

The model generates a 2-3 sentence narrative in plain English, suitable for a general audience. Tone: clear, factual, accessible. The prompt is engineered to:

- Avoid speculation
- Ground every observation in the provided data
- Use plain language over financial jargon
- Refuse to narrate if the structured event data is incomplete

Each narration is cached by event hash, so re-running a day's pipeline doesn't re-narrate identical events.

---

## Temporal pipeline

Runs nightly at 6:00 AM ET, after T+1 HIST data becomes available:

```
DownloadHistActivity         (HTTPS download from iextrading.com/api/1.0/hist)
  └─ DecompressActivity      (gunzip; heartbeats during decompress)
      └─ ParseTopsActivity   (Java parser → events hypertable in Postgres via JDBC COPY; heartbeats)
          └─ ValidateDailyTotalsActivity   (parser sanity-check vs IEX daily summaries)
              └─ RefreshBaselinesActivity  (refresh TimescaleDB continuous aggregates for T-30 windows)
                  └─ ScoreEventsActivity
                      └─ SelectTopEventsActivity
                          └─ NarrateEventsActivity   (calls llama-large.joi)
                              └─ StoreNarrativesActivity   (Postgres narratives table)
                                  └─ InvalidateCacheActivity   (frontend bust)
```

Each activity has retry policies, appropriate timeouts, and heartbeating for long-running operations. Failed workflows are visible in the Temporal UI and replayable from any activity.

---

## API

FastAPI on `long-exposure-prod-api.luv`:

```
GET /api/v1/market/today
GET /api/v1/market/{date}
GET /api/v1/market/{date}/events
GET /api/v1/ticker/{symbol}/history
GET /api/v1/event/{event_id}                # includes the score breakdown
GET /api/v1/health
```

Structured JSON logging via `structlog`; auto-shipped to Loki by the workspace promtail.

---

## Frontend integration

Long Exposure does **not** ship its own frontend. The unified portal at [vedanta.systems](https://vedanta.systems) (sources in `~/workspace/dev/vedanta-systems/`, React + TypeScript + shadcn/ui) hosts a per-project browser component that consumes this project's API.

The integration contract:

- This repo exposes the API container on the workspace `proxy` network as `long-exposure-prod-api.luv` (and `long-exposure-dev-api.luv` for the dev stack).
- `vedanta-systems` adds an nginx location for `/api/long-exposure/*` that proxies to the API container, mirroring the existing `/api/found-footy/*` and `/api/spin-cycle/*` blocks.
- A new `src/components/long-exposure-browser.tsx` in `vedanta-systems` consumes `/api/long-exposure/v1/*` and renders the timeline UI.

Design principles for the browser component (lives in `vedanta-systems`): clean, minimal, timeline-first, mobile-friendly, dark mode by default. Each event card exposes the score breakdown ("why this event made the cut") on hover/tap.

---

## Open source positioning

The repository is MIT-licensed from day one. The README frames the project as a *reference implementation of the IEX TOPS parser in Java*, with the public daily-narrative site as a working demo. This is on-brand for IEX's transparency mission and avoids the "portfolio piece" framing.

Repo layout:

- `parser/` — the Java module (also hosts the Temporal worker registration)
- `api/` — FastAPI service
- `deploy/` — host-level integration notes (Caddy, vedanta-systems wiring)
- `docs/` — agent + contributor docs (architecture, plan, todo, decisions, protocol-notes, operations)
- `docker-compose.yml` / `docker-compose.dev.yml` — full stack reproducible by anyone

Frontend is not part of this repo; see [Frontend integration](#frontend-integration).

---

## 22-day build plan

Adjusted from a 20-day plan: added 1 day to event scoring (the hardest tuning problem), added 1 day to validation harness work (do this in parallel with parser dev, not after).

**Days 1–3 — Foundation**
Set up Gradle project. Multi-stage Dockerfile. Implement `PcapReader` using pcap4j. Parse IEX-TP headers. Get raw message bytes printing to stdout from a real HIST file. Open the GitHub repo public on Day 1.

**Days 4–7 — TOPS parser + validation harness (in parallel)**
Implement `TopsMessageRouter` and all core message decoders. Validate price decoding. Unit tests for each decoder. Start the `DailyTotalsValidator` immediately — every new decoder gets a corresponding validation check. Don't wait until the end.

**Days 8–10 — Postgres + TimescaleDB + storage**
Stand up Postgres 16 with the TimescaleDB extension on luv (per the workspace conventions: container on `proxy` network, no host ports; Adminer container alongside for ad-hoc SQL access). Schema for nanosecond-precision TOPS events as a TimescaleDB hypertable. Implement `TimescaleWriter` in Java using JDBC `COPY` for bulk insert. Parse a full day's HIST file end-to-end and verify the data lands.

**Days 11–13 — Baseline data + bootstrap**
Implement the rolling-baseline calculation. Backfill against 30 days of historical HIST. Verify baselines look sensible by spot-checking known events (a real halt from the past month should score high; a routine quote on a quiet day should score low).

**Days 14–17 — Event scoring**
Implement `EventScorer`. Define significance tiers and scoring weights. Every event surfaces its score breakdown in the data model so it's exposable in the UI and inspectable in tests. Tune until the top-N events on a sample day pass an honest "would I read this?" check.

**Days 18–19 — LLM narration**
Prompt engineering for `llama-large.joi`. Implement the narration pipeline with caching by event hash. Generate and review narratives for a week of historical events. Iterate on prompt until the output reads like a sober finance journalist, not an excited blog.

**Days 20–21 — Temporal + API**
Wire pipeline stages into Temporal workflow with proper retry policies and heartbeating. Implement FastAPI endpoints. Full end-to-end pipeline test: download → parse → validate → score → narrate → store → publish.

**Day 22 — vedanta-systems integration + deploy**
In `~/workspace/dev/vedanta-systems/`: add the `/api/long-exposure/*` nginx proxy, build `src/components/long-exposure-browser.tsx` consuming the API, register the project in `src/App.tsx`. Bring up the production stack on luv. Public launch with 30 backfilled days already narrated and live at `vedanta.systems`.

---

## What's not in the v1

- DEEP feed (depth-of-book) — phase 2 if deeper liquidity analysis is added
- Real-time streaming (HIST is T+1 only; real-time requires the IEX SIP feed which is a different licensing model)
- Multi-exchange comparison — IEX only for v1
- User accounts / saved searches / alerts — purely read-only public for v1
- Mobile app — responsive web is enough

---

## Notes

This project lives in the workspace conventions:

- All containers attach to the workspace `proxy` network for HTTP routing; data services use the project-internal network only
- Internal ports default to upstream image defaults (no manual port allocation; the workspace migration eliminated that pattern)
- Logs flow to `monitor-loki.luv` automatically via promtail
- Container metrics scraped by `monitor-prometheus.luv` automatically via cAdvisor
- LLM access via `llama-large.joi` — already wired, no additional setup

See `~/workspace/proxy/README.md` for the conventions reference and `~/workspace/RECOVERY.md` for disaster-recovery context.
