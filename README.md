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

It's open source from day one — MIT licensed — positioned as **the first public reference implementation of the IEX DEEP+ (DPLS) parser in any language**, with a public daily-narrative demo on top. (Verified 2026-05-11: no open-source DEEP+ parser exists in any language on GitHub.)

---

## Architecture

### Service layout

Every service runs in Docker. No host ports published for HTTP services — the workspace's shared `proxy` (Caddy) handles routing by hostname on the tailnet. Logs auto-flow to Loki via Promtail, container metrics via cAdvisor — no per-service instrumentation needed for v1.

Long Exposure does not host its own frontend AND does not host its own HTTP API. The UI surfaces inside the unified portal at `vedanta.systems` (served by `~/workspace/dev/vedanta-systems/`); the `/api/long-exposure/*` routes are served by vedanta-systems' unified Express API, which connects directly to long-exposure's Postgres over the shared `luv-{dev,prod}` docker network. See [Frontend integration](#frontend-integration) below.

| Service | Container | URL (tailnet) | Internal port | Node |
|---|---|---|---|---|
| Worker (Java + Temporal SDK) | `long-exposure-prod-worker` | — (no HTTP) | — | luv |
| Temporal server | `long-exposure-prod-temporal` | — (gRPC only, internal) | 7233 | luv |
| Temporal UI | `long-exposure-prod-temporal-ui` | `long-exposure-prod-temporal-ui.luv` | 8080 | luv |
| Postgres + TimescaleDB | `long-exposure-prod-postgres` | — (PG wire only, on `long-exposure-prod` + `luv-prod`) | 5432 | luv |
| Adminer (Postgres web UI) | `long-exposure-prod-adminer` | `long-exposure-prod-adminer.luv` | 8080 | luv |
| LLM (chat) | `llama-large` (existing) | `llama-large.joi` (already running) | 8080 | joi |

### Why Postgres + TimescaleDB

Postgres 16 with the TimescaleDB extension. Hypertables for the events table, partitioned on the trade-time column. Continuous aggregates for the rolling T-30 baselines so they don't have to be recomputed from raw events on every pipeline run. Standard `COPY` for bulk ingest from the Java parser via JDBC.

This is the pragmatic choice over a pure time-series DB:

- **Mature operational story.** `pg_dump` for logical backups, WAL archiving for point-in-time recovery, `pg_basebackup` for filesystem-level snapshots. Every backup tool, every monitoring integration, every blog post on schema migrations works without translation.
- **Ecosystem.** Drivers, ORMs, GUI tools, error messages with thousands of Stack Overflow answers. Friction is near zero.
- **Familiar mental model.** Debugging time stays in the project, not in learning a new query engine.
- **Future flexibility.** If Long Exposure grows orthogonal features (user accounts, saved searches, full-text search on narratives), Postgres absorbs them without adding a second database.

What we give up vs a purpose-built TSDB: ~10–20% raw scan throughput on time-range queries, and roughly 2–5× slower bulk ingest. Both are completely irrelevant at our data scale (~7–9 GB compressed per day for TOPS, ingested once nightly, served to a personal-site-scale read load — ~130 GB of parsed events across the week-aligned 2-week rolling window, well within Postgres' comfort zone).

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

## Data feed selection — TOPS, DEEP, DPLS

IEX publishes three historical data feeds via HIST, all listed at `https://iextrading.com/api/1.0/hist`:

| Feed | What it carries | History available | Compressed size/day |
|---|---|---|---|
| **TOPS 1.6** | Top of book — best bid/ask + last trade + status/halt events | 2017→ | ~7–9 GB |
| **DEEP 1.08** | Full depth of book — every displayed price level + aggregated sizes | 2017→ | ~7–9 GB |
| **DPLS 1.0** (DEEP+) | Order-by-order — every individual displayed order's add/modify/cancel/execute | Jan 2025→ | ~7–9 GB |

Sizes are roughly equivalent across feeds. `DPLS` is the filename token for what IEX's spec calls "DEEP+" — same product, the filename uses `DPLS` because `+` is awkward in filenames.

Information-theoretically, **DPLS ⊃ DEEP ⊃ TOPS**: DEEP's price-level book can be derived from DPLS by aggregating order sizes; TOPS's BBO can be derived from DEEP's best price level on each side. So storing more than one feed for the same date is pure duplication.

**Decision: DEEP+ / DPLS 1.0 for v1.** The reasoning lives in `docs/decisions.md`. The short version: the morning's "TOPS first, DEEP+ phase 2" plan rested on assuming each feed was 2–3 weeks of work. Real execution pace compressed Days 1–10 of the original plan into one session. With that velocity, deferring DEEP+ no longer makes sense — and the *order-by-order* narratives DEEP+ unlocks ("8 orders posted and cancelled within 50ms — classic spoof shape", "median order time-in-book on SPY collapsed from 800ms to 90ms") are dramatically more on-brand for IEX's transparency mission than the generic top-of-book narratives any feed would support.

**TOPS is the validation oracle, not deferred.** The TOPS parser, message decoders, schema, and 285M-row dataset already loaded for 2026-05-08 stay in the repo — repurposed as the cross-validation reference. Same trading day, derive top-of-book from DEEP+ book state, diff against the real TOPS Quote Update stream. Same logic for trade size sums. Where they agree, the DEEP+ decoder is correct on the overlapping surface (which covers every event type TOPS publishes).

**Out of scope.** TOPS / DEEP / DEEP+ all have companion **SNAP** specifications. SNAP is a TCP request-response service for live consumers joining the multicast mid-day to recover the current book state. It does not appear in HIST and is irrelevant to a T+1 batch pipeline.

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

The narration step takes selected scored events and produces a 1-3 sentence prose paragraph per event. The model is `Qwen3.5-122B-A10B` (10B active MoE) served via llama.cpp on the homelab's `joi` node. Per-event pipeline is three passes; only the first two are LLM calls.

### Pass 1 — `BlueprintExtractor`

Takes the scored event's full `breakdown` JSON and asks the model to emit a JSON blueprint listing the facts that will be narrated. Each fact has a `source_field` pointing back to a key in the breakdown — the contract that the verifier later enforces. Sampling preset `EXTRACT` (temperature 0.1, top_p 0.8, top_k 20) — deterministic JSON is wanted here, not prose variety.

After the LLM call returns, code deterministically copies `breakdown.company_name` into the blueprint as a pass-through field. The model never decides whether to include it — the data flows through cleanly.

### Pass 2 — `ProseRenderer`

Takes the blueprint and emits a JSON object with three semantic slots:

```json
{
  "lead":         "<one sentence — subject + action + ≥1 key_number>",
  "facts":        ["<sentence>", "<sentence>", ...],
  "co_occurring": "<one sentence>" | null
}
```

Schema is enforced at the sampler via `response_format: json_schema, strict: true` — llama.cpp guarantees structurally valid output, no parse-retry path. Sampling preset `RENDER` (Qwen's published "Instruct mode for general tasks" — temperature 0.7, top_p 0.8, top_k 20, presence_penalty 1.5).

The slot structure exists because earlier free-form prose iterations leaked qualitative filler ("the security is now trading normally", "marking the end of one of the longer halts") when the breakdown was thin. The model had unconstrained "freestyle space" to fill. Structured slots eliminate that space by construction — every output token lives in a named slot. The `co_occurring` slot is required (nullable in type) and references nested events of *other* scorer types that fell inside the parent event's window.

Code stitches the slots: `lead + " " + facts.join(" ") + (co_occurring ? " " + co_occurring : "")`. The structured JSON is persisted alongside the prose in `narratives.render_structured`.

### Pass 3 — `GroundingVerifier` (pure code)

Four-layer check, no LLM:

1. **Blueprint key_numbers ⊆ breakdown** — every `source_field` reference must resolve to a real path in the breakdown (supports dotted paths for nested `co_occurring` data).
2. **Prose numbers ⊆ blueprint ∪ breakdown** — every numeric token in prose must appear in either, using `BigDecimal.stripTrailingZeros` for case "$431.00" ↔ "431".
3. **Event symbol present in prose** — catches symbol fabrication (the ODDTX-from-ODTX class).
4. **Prose company name agrees with `breakdown.company_name`** — token-subset comparison after dropping entity-type / filing-decoration tokens. Accepts "Intel Corp." ↔ "Intel Corporation"; rejects "Oculus Dynamics Inc." ↔ "Odyssey Therapeutics, Inc." Catches the model substituting a different company from training memory.

Verifier failures don't abort the pipeline — the prose is still produced and stored with `verifier_passed = false`, available for downstream filtering ("don't publish failed narrations").

### Caching

Each narration is keyed by `event_hash` = SHA256 of `scorer_id + breakdown + extract_prompt_version + render_prompt_version`. Before the LLM call, the activity checks for an existing `verifier_passed` row with that hash — a hit **skips the two LLM calls entirely** (genuine compute-skip, not just storage idempotency). So re-scoring or backfilling a day only re-narrates the events whose breakdown or prompt version actually changed; bumping either prompt version invalidates the cache and forces re-narration. INTERPRET and AGGREGATE are content-addressed the same way (`interpretation_hash`, `weekly_aggregate.content_hash`). This is what makes the uniform re-run incremental rather than a from-scratch 12-hour pass.

### Concurrency

`llama-large.joi` is a single-GPU local model — throughput collapses above 2 concurrent decode streams. Three layers of guard:

- **JVM-wide `Semaphore(2, fair)`** inside `LlamaClient` — the hard physical guard.
- **Worker-side cap** (`setMaxConcurrentActivityExecutionSize(2)`) on the dedicated narration task queue.
- **Workflow-side sliding window** (`Promise.anyOf` over an in-flight list) so the Temporal UI shows only ~2 activities scheduled at any moment instead of all 164.

Throughput is ~5 narrations/minute end-to-end (each event ≈ 2 LLM calls × ~10–20 sec at 2 concurrent).

---

## Temporal pipeline

`DailyPipelineWorkflow` runs nightly at 00:00 America/New_York (Tue–Sat, paused by default). T+1 HIST data is reliably available between 22:32 ET and 00:11 ET; midnight gives a 3-hour retry budget on URL resolution without spilling into morning.

The orchestrator is *pure orchestration* — calls child workflows for each phase plus `PipelineRunRecorderActivity` for cross-cutting metadata:

```
DailyPipelineWorkflow
  ├── DownloadWorkflow      (3 IEX HIST URLs resolved + 3 .pcap.gz downloaded, all parallel)
  ├── ParseWorkflow         (DPLS → 14 hypertables via JDBC COPY)
  ├── ValidateWorkflow      (runs in parallel with Parse; 3 cross-check legs:
  │                          DPLS↔DEEP price-level, DPLS→TOPS BBO, DEEP→TOPS BBO)
  ├── ScoreWorkflow         (RefreshBaselines → MaterializeOrderLifecycle → 9 scorers
  │                          → EnrichWithCoOccurrence → EnrichAnalytics → SelectTopEvents)
  ├── NarrateWorkflow       (DESCRIBE — per-event fan-out, 2-concurrent, verifier-retry ×3)
  ├── InterpretWorkflow     (INTERPRET — per-event surrounding-context LLM, verifier-retry ×3)
  ├── SynthesizeDayWorkflow (SYNTHESIZE — one LLM call across the day's events)
  ├── AggregateWeekWorkflow (AGGREGATE — recompute-daily "week-so-far" rollup)
  ├── CompressChunksActivity(compress today's chunks → ~13 GB/day on disk)
  └── CleanupWorkflow       (delete .pcap.gz + week-aligned 2-week retention sweep)
```

Each phase is its own child workflow, independently invokable for replay or backfill. The LLM-bearing phases (DESCRIBE / INTERPRET / SYNTHESIZE / AGGREGATE — daily, weekly, quarterly, yearly) are strictly sequential — they share `llama-large.joi`'s 2 GPU slots, so only one LLM workflow runs at a time. The quarterly + yearly rollups sit dormant (return 0 without an LLM call) until enough lower-tier rollups accumulate. Per-activity retries, heartbeats, and start-to-close timeouts. Full layout including all 26 activities + their retry policies in [`docs/temporal-design.md`](docs/temporal-design.md).

Ancillary workflows on the same task queue:

- `RefreshSymbolsWorkflow` — weekly cron (Sun 02:00 ET, paused). Pulls ticker metadata from NASDAQ (`nasdaqlisted.txt` + `otherlisted.txt`), SEC EDGAR (`company_tickers.json`), and the local IEX SecurityDirectory. The 3-source overlay produces the `symbols` reference table used by `Enrich.symbol()` at score time.
- `MaterializeWorkflow` / `SelectWorkflow` / `NarrateWorkflow` — ad-hoc replay entry points.

The original design considered a longer linear chain (DownloadHist → Decompress → ParseTops → ValidateDailyTotals → RefreshBaselines → ScoreEvents → SelectTopEvents → NarrateEvents → StoreNarratives → InvalidateCache). Real evolution compressed that into 6 phase workflows + the orchestrator, with Validate running in parallel with Parse (validators read the raw `.pcap.gz` files directly, not the DB). Decompress disappeared entirely — `PcapReader` streams `.pcap.gz` via `GZIPInputStream`.

---

## API

Public read-only API, served by vedanta-systems' unified Express service (`~/workspace/dev/vedanta-systems/src/server/routes/long-exposure.ts`):

```
GET /api/long-exposure/health             # narratives_total + dates_available
GET /api/long-exposure/latest             # ISO date of most recent narrated day
GET /api/long-exposure/dates              # all dates with narrative counts
GET /api/long-exposure/day/{YYYY-MM-DD}   # narratives grouped by scorer for that day
GET /api/long-exposure/symbol/{symbol}    # narratives for a ticker across days
GET /api/long-exposure/event/{id}         # full detail: prose, blueprint, breakdown
```

All endpoints are read-only — no write/refresh/admin surface — so the public `/api/long-exposure/*` URL space is safe by construction. The vedanta-systems Express service is the only HTTP front-door; the Long Exposure worker is the only write path (Temporal cron + activities).

---

## Frontend integration

Long Exposure does **not** ship its own frontend AND does not ship its own HTTP API. Both live in the unified portal at [vedanta.systems](https://vedanta.systems) (sources in `~/workspace/dev/vedanta-systems/`, React + TypeScript + shadcn/ui + Express):

- `src/server/routes/long-exposure.ts` — Express router for `/api/long-exposure/*`, connects to `long-exposure-{dev,prod}-postgres:5432` over the shared `luv-{dev,prod}` docker network.
- `src/components/long-exposure-browser.tsx` — the per-project browser component consuming those endpoints.
- `src/types/long-exposure.ts` — response shapes.
- Project entry registered in `src/App.tsx` under `~/workspace/long-exposure`.

Same shape as the existing `found-footy-browser` and `spin-cycle-browser` integrations, except long-exposure has no inbound submission surface (it's a fully autonomous pipeline; users only read).

Design principles for the browser component (lives in `vedanta-systems`): clean, minimal, timeline-first, mobile-friendly, dark mode by default. Each event card exposes the score breakdown ("why this event made the cut") on hover/tap.

---

## Open source positioning

The repository is MIT-licensed from day one. The README frames the project as **the first open-source reference implementation of the IEX DEEP+ (DPLS) parser in any language**, with the public daily-narrative site as a working demo. The TOPS parser is kept as the validation oracle (and as a contribution to the existing open-source ecosystem alongside it). This positioning is on-brand for IEX's transparency mission and avoids the "portfolio piece" framing.

Repo layout:

- `parser/` — the Java module: parser + Temporal worker + scorer registry + narration pipeline + verifier. The whole pipeline-side codebase.
- `deploy/` — host-level integration notes (Caddy entries on the workspace proxy, vedanta-systems API wiring).
- `docs/` — design + operational docs: [concepts](docs/concepts.md), [architecture](docs/architecture.md), [scoring-and-narration](docs/scoring-and-narration.md), [decisions](docs/decisions.md), [protocol-notes](docs/protocol-notes.md), [temporal-design](docs/temporal-design.md), [operations](docs/operations.md), [validation-results](docs/validation-results.md), [plan](docs/plan.md), [todo](docs/todo.md).
- `docker-compose.yml` / `docker-compose.dev.yml` — full stack reproducible by anyone.

There is no `api/` directory in this repo. The HTTP API surface lives in [`vedanta-systems`](https://github.com/vedantadhobley/vedanta-systems)' unified Express service, which queries this project's Postgres directly over the shared docker network. See [Frontend integration](#frontend-integration).

Frontend is not part of this repo; see [Frontend integration](#frontend-integration).

---

## Build status

The original 22-day plan compressed substantially. Current state (2026-05-27):

✅ **Foundation + parser** (2026-05-11). Pure-Java pcap-ng reader, IEX-TP transport decoder, 7 shared admin message decoders, 5 TOPS trading decoders + `TopsMessageRouter`, 7 DPLS trading decoders + `DplsMessageRouter`, DEEP price-level update decoder + `DeepMessageRouter`. Order-book + price-level-book state machines. Full unit test suite (~80 tests).

✅ **Cross-validation triangle** (2026-05-11/12). Three independent validators — DPLS↔DEEP price-level (the TOPS-independent leg, 100.0000% on two trading days), DPLS→TOPS derived BBO (99.4184%), DEEP→TOPS derived BBO (identical to DPLS leg). Full residual analysis in [`docs/validation-results.md`](docs/validation-results.md).

✅ **Storage** (2026-05-12). 13 wire-format hypertables, `order_lifecycle` derived hypertable for sub-second PostCancel/Layering scans, 7 standard tables, `daily_volume_by_symbol` continuous aggregate. End-to-end: 364 M rows ingested in 35:07 (~174 K rows/sec) via JDBC `COPY`.

✅ **Temporal pipeline** (2026-05-13, extended through 2026-05-29). 16 workflows on the daily-pipeline task queue (incl. `PipelineWorkflow` — unified entry point covering cron + ad-hoc + backfill, replaces all `scripts/rerun-dataset-*.sh` shell drivers), 26 activities. Per-activity retry policies, heartbeats, start-to-close timeouts. Idempotent on pipeline_run with pre-clean by trading_date. The full calendar rollup hierarchy (week → quarter → year) is wired into the daily pipeline; quarterly + yearly tiers sit dormant until enough lower-tier rollups accumulate.

✅ **Scoring + selection** (2026-05-16, extended through 2026-05-27). 9 scorers — 7 intraday (halt, large_trade, sweep, post_cancel_cluster, layering, iceberg, liquidity_withdrawal) + 2 inter-day (`volume_deviation` 2026-05-25; `time_in_book_drift` 2026-05-27 reading a durable `daily_lifetime_by_symbol` baseline) — implementing push-model `EventScorer`. Selection via within-scorer percentile rank → ~90–170 narratable events per trading day. A shared pure-function `com.longexposure.analytics.Analytics` layer + post-select `EnrichAnalyticsActivity` (windowed + book-replay) feeds genuinely sophisticated grounded metrics into every breakdown — order-to-trade ratio, OFI, slippage + reversion, effective spread, realized vol, burstiness, depth-imbalance, % of book removed + recovery, iceberg display-ratio, robust-z/percentile, HHI/entropy, and slice-caveated VPIN/Kyle's-λ. See [`docs/analytics-catalog.md`](docs/analytics-catalog.md).

✅ **Symbol enrichment** (2026-05-18, expanded 2026-05-21). `RefreshSymbolsWorkflow` pulls from three sources: NASDAQ public listings, SEC EDGAR (canonical company names), and the local IEX SecurityDirectory. `CompanyNameNormalizer` handles the long tail.

✅ **Co-occurrence enrichment** (2026-05-20). Long sec-scale parent events (liquidity_withdrawal) absorb nested ms-scale children (post_cancel_cluster, layering) into a `co_occurring` block on the parent's breakdown. Children marked `subsumed_by_event_id` and skipped at selection.

✅ **Two-pass narration (DESCRIBE) with structured output + 4-layer verifier** (2026-05-21). `BlueprintExtractor` → `ProseRenderer` (JSON-schema-enforced three-slot output) → pure-code `GroundingVerifier`. Running against `Qwen3.5-122B-A10B` on `joi`. The 11-day launch dataset (2026-05-08 + 05-11..05-15 + 05-18..05-22, ~2,400 narratives) verifies at 100% across the full chain after the v15 holistic refactor (see Round 8 below).

✅ **Per-event interpretation (INTERPRET)** (2026-05-22). `InterpretEventActivity` reads the breakdown + a ±60-sec pre/post trade window and adds sequential/causal context DESCRIBE can't ("the block was followed by another 47 sec later"). `InterpretationVerifier` grounds every number against breakdown ∪ windows.

✅ **Daily synthesis (SYNTHESIZE)** (2026-05-22). One LLM call per day across all per-event interpretations → a cross-event "today's themes" paragraph. `SynthesisVerifier` (ticker-fabrication + magnitude-tolerant number grounding).

✅ **Weekly rollup (AGGREGATE)** (2026-05-26). Recompute-daily "week-so-far" — reads this week's daily syntheses + the prior ~8 weekly rollups for week-over-week trend, content-addressed so the daily recompute is incremental. Wired into the daily pipeline after SYNTHESIZE.

✅ **Durable inter-day baselines** (2026-05-26). `daily_volume_by_symbol` cagg refresh window extended to 400 days — a continuous aggregate outlives the 2-week wire retention, so per-symbol baselines persist ~1 year. `BaselineProvider` decouples scorers from the cagg SQL; `RefreshBaselinesActivity` keeps it current before scoring.

✅ **Verifier-driven retry + grounding polish** (2026-05-27). All four LLM stages re-roll a verifier-rejected call up to 3× (sampling variance clears transient number-glitches) → ~100% verifier-passed without weakening the verifier. Notional values pre-formatted as `$X,XXX.XX`; per-event content-addressed skip makes re-scores/backfills incremental.

✅ **Retention + 2-week dataset** (2026-05-25/27). Week-aligned rolling 2-full-weeks retention (the narrative archive is kept indefinitely; only the heavy wire substrate ages out). Two full weeks loaded (05-11→15, 05-18→22) + 05-08, re-run uniform on the current formatting/retry.

✅ **Round 6 — prose-quality polish** (2026-05-30). Clean clock times via `BreakdownFmt.toEtTime()` (`HH:mm:ss.SSS` → `HH:mm` kills millisecond leaks universally), prose-ready categorical labels per scorer (`halt_duration_bucket_label`, `withdrawal_side_class` hyphenated, `halt_phase_span_label` as a grammatical phrase), complete time-anchor coverage. Codifies the load-bearing principle "pre-format awkward values at the data layer, not in the prompt".

✅ **Round 7 — structured data tables on daily + weekly views** (2026-05-30). Deterministic JSONB `data_table` column on `daily_synthesis` + `weekly_aggregate` that renders ABOVE the LLM prose for journalist-format scannable reading. Pure SQL computed inside the activities — no extra LLM cost. Daily carries `executive_summary` (5 bullets), `headline` (top 5 events), `per_scorer_top`, `day_summary`, `notable_extremes`, `vs_prior_day`. Weekly adds `executive_summary`, `headline_events`, `per_day` breakdown, `top_symbols` with day-presence + scorer-mix, `vs_prior_week`. Pattern-codifies the "data view of the data table" distinction (no LLM-generated counts; the structured view IS the count surface, the prose IS the character surface).

✅ **Round 8 — wrap-up day** (2026-05-30 → 2026-05-31). Schema-guard once-per-JVM fix (eliminates Postgres `pg_type` ACCESS-EXCLUSIVE lock contention from concurrent activities); canonical vocabulary discipline across all four LLM tiers (baseline references, multipliers, slippage, depth removal, display ratio, order-to-trade ratio); structural streak whitelist on AGGREGATE rollups (`StreakPhrasings.allowedStreakPhrasings()` — model picks a verbatim form from a computed whitelist or omits, replacing arithmetic-trust language); non-ASCII verifier check (`ProseCharCheck` catches multilingual leakage like a Chinese fragment that the model occasionally emits under sampling pressure); production-path validation via `PipelineWorkflow` (the schedule was switched from legacy `DailyPipelineWorkflow` to `PipelineWorkflow` mid-cycle and verified). Full chain ran 11 days × LLM_CHAIN in 11h 41m via the unified entry point. Operational rule established: `PipelineWorkflow` is THE operator-facing entry; the legacy and leaf child workflows are INTERNAL.

✅ **Round 8.1 — v15 holistic prompt refactor** (2026-05-31). The deepest prompt-engineering insight of the project. User-pushback audit revealed that 6 of 11 daily syntheses opened with "regime shift" framing centered on overnight / pre-market activity. Root cause: `Catalog.Entry.canonicalInterpretation` was a prose string the model copied as a template (19/19 inter-day INTERPRETs on 2026-05-22 contained "regime shift"), the SYNTHESIZE system prompt had the phrase as a positive example, and the chronological event sort pushed inter-day signals (timestamped at end-of-prior-day) before regular-session events. Fix: replace `canonicalInterpretation` with `documentedDrivers` (a `List<String>` the prompt assembles), trim heavy DO/DON'T rules, restructure SYNTHESIZE user prompt with phase-grouped score-ordered events and a separate DAY-LEVEL SIGNALS section. Empirical result on 05-20 single-day test: inter-day "regime shift" rate dropped 100% → 39%, SYNTHESIZE lede became the model's own framing ("A day defined by structural liquidity vacuums") instead of catalog template echo. The transferable insight: examples in prompts become templates — especially for smart models. Replace prose strings with structured data; trim DO/DON'T lists; trust the model's journalism training. Full reasoning in [`docs/decisions.md`](docs/decisions.md) 2026-05-31.

🛠 **Public launch prep** (in progress). Frontend integration into the `vedanta-systems` portal + whitepaper.

📋 **Queued post-launch (Round 9 / Round 10 candidates).**
- **Cross-symbol context expansion** — INTERPRET prose could reference correlated symbols' activity within a ±5 min window ("AMD layering at 14:23 while NVDA and INTC saw sweeps in the same window — semiconductor sector"). The architecture today has a hard "single-symbol grounding" model that this would dissolve. Build path requires sector classification on `symbols` table (SEC EDGAR SIC codes + hand-curated GICS overlay), correlated-symbol resolver via ETF top-10 holdings, cross-symbol event-window query in `TradeWindow`, INTERPRET prompt extension, and verifier extension for symbol-aware grounding. Estimated 16-20 hr code + joi time. Deferred to post-launch so we can validate sector classification against real user reactions and properly design the verifier expansion. Documented as a whitepaper-flagged gap.
- Monthly numeric + prose rollup tiers (need months of history); per-metric meaningfulness assessment of VPIN/Kyle's-λ on the IEX slice from the launch data; iceberg DESCRIBE "display ratio" canonical phrasing tighten; shortened-source-field model behavior on `co_occurring` nested paths.

See [`docs/plan.md`](docs/plan.md) for sprint-by-sprint history and [`docs/todo.md`](docs/todo.md) for the active work list.

---

## What's not in the v1

- DEEP / TOPS-only historical analysis for pre-2025 dates (DPLS history starts Jan 2025; multi-year analysis would require falling back to those feeds)
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
