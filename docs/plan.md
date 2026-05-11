# 22-day Build Plan

The day-by-day schedule for going from "scaffolded repo" to "live narrated public daily-market feed surfaced inside vedanta.systems."

Status legend: `[ ]` not started, `[~]` in progress, `[x]` complete.

This plan is the agent-editable source of truth. The README has a condensed prose version; if they conflict, this file wins.

## Days 1–3 — Foundation

- [x] Set up Gradle Kotlin DSL project (`build.gradle.kts`, `settings.gradle.kts`)
- [x] Multi-stage Dockerfile (gradle JDK21 builder → JRE alpine + libpcap)
- [x] Open the GitHub repo public on Day 1 (MIT licensed)
- [ ] Implement `pcap.PcapReader` using pcap4j — open a `.pcap.gz` and stream packets
- [ ] Implement `transport.IexTpDecoder` — parse the 40-byte IEX-TP header
- [ ] Get raw TOPS message bytes printing to stdout from a real HIST file

## Days 4–7 — TOPS parser + validation harness (in parallel)

- [ ] Implement `tops.TopsMessageRouter` — dispatch on the 1-byte message type after the 2-byte length prefix
- [ ] Decoder + value object for each TOPS message type (see @protocol-notes.md for byte-level layouts):
  - [ ] System Event (`S`)
  - [ ] Security Directory (`D`)
  - [ ] Trading Status (`H`)
  - [ ] Retail Liquidity Indicator (`I`)
  - [ ] Operational Halt Status (`O`)
  - [ ] Short Sale Price Test Status (`P`)
  - [ ] Quote Update (`Q`)
  - [ ] Trade Report (`T`)
  - [ ] Trade Break (`B`)
  - [ ] Official Price (`X`)
  - [ ] Auction Information (pages 21–23 of TOPS 1.66 spec — TBR after first read of those pages)
- [ ] Validate price decoding (8-byte signed int with 4 implied decimals, little-endian)
- [ ] Unit tests for each decoder (junit-jupiter is already in deps)
- [ ] **`validation.DailyTotalsValidator`** — cross-checks against IEX's published per-symbol summaries. Don't wait until the end; every new decoder gets a paired check.
- [ ] **Reference-implementation cross-check**: integrate `WojciechZankowski/iextrading4j-hist` as a test dependency (Java, same JVM); for each sample TOPS .pcap.gz, run both parsers and diff message streams. Any divergence = bug (usually ours). See @protocol-notes.md "Reference implementations" section.

**Shared decoder note**: 7 of these messages (`S`, `D`, `H`, `I`, `O`, `P`, plus `E` Security Event which is DEEP/DEEP+ only) are byte-identical across all three feeds. Structure the package so admin decoders live in `com.longexposure.admin.*` and per-feed trading decoders live in `com.longexposure.tops.*` (and later `com.longexposure.deepplus.*`). This is the main forward-compat investment for phase 2.

## Days 8–10 — Postgres + TimescaleDB + storage

- [ ] Write `parser/src/main/resources/schema.sql` defining:
  - [ ] `events` table → `create_hypertable` on trade-time column
  - [ ] `narratives` table keyed by event hash
  - [ ] Continuous aggregates for the T-30 baselines (symbol × metric)
- [ ] Apply schema on worker startup (or via a one-shot Temporal activity)
- [ ] Implement `storage.TimescaleWriter` — JDBC `COPY` for bulk insert from the parser
- [ ] Parse a full day's HIST file end-to-end and verify the data lands. Spot-check via Adminer.

## Days 11–13 — Baseline data + bootstrap

**Concept refresher** (full version in @decisions.md addendum). The scorer flags events as "unusual" by comparing to per-symbol rolling 30-trading-day averages of: daily total volume, daily trade count, average best-bid-best-ask spread, halt frequency, and intraday volume distribution. TimescaleDB **continuous aggregates** maintain these incrementally so the scorer doesn't recompute from raw events on every run.

Bootstrap problem: on day-1-live we have zero history in DB, so every baseline is undefined. Fix is one-time backfill against 30 days of HIST data before launch. After backfill, baselines exist; launch-day narratives use them. The same 30 backfilled days double as the visible launch archive.

- [ ] Implement the rolling-baseline calculation via TimescaleDB continuous aggregates (per-symbol, per-metric, rolling 30 trading days)
- [ ] One-time bootstrap: download + parse the last 30 trading days of TOPS HIST data into the events hypertable
- [ ] Re-run the scorer + narrator over those 30 backfilled days (now with baselines defined) to produce the visible launch archive
- [ ] Spot-check baselines: a real halt from the past month should score high; a routine quote on a quiet day should score low

## Days 14–17 — Event scoring

- [ ] Implement `scoring.EventScorer` with the significance dimensions documented in the README:
  - [ ] Event-type weighting
  - [ ] Ticker liquidity tier
  - [ ] Deviation from baseline
  - [ ] Time-of-day weighting
  - [ ] Duration
- [ ] Every event surfaces its score breakdown in the data model (visible in API responses for inspection)
- [ ] Tune until the top-N events on a sample day pass the "would I read this?" check

## Days 18–19 — LLM narration

- [ ] Implement `NarrateEventsActivity` calling `llama-large.joi` (OpenAI-compatible)
- [ ] Cache by event hash in the `narratives` table
- [ ] Prompt engineering: factual, plain-language, refuse on incomplete data
- [ ] Generate + review narratives for a week of historical events; iterate prompt

## Days 20–21 — Temporal + API

- [ ] Wire `Main.java` as the Temporal worker registration (replaces the Day-1 stub)
- [ ] Implement the workflow + activity classes (see @architecture.md for the activity list)
- [ ] Retry policies + heartbeating per activity
- [ ] Implement the FastAPI v1 endpoints listed in @../README.md
- [ ] End-to-end pipeline run on a single trading day

## Day 22 — vedanta-systems integration + deploy

Cross-repo work in `~/workspace/dev/vedanta-systems/`:

- [ ] nginx location for `/api/long-exposure/*` → `long-exposure-prod-api:3001`
- [ ] `src/components/long-exposure-browser.tsx` — timeline UI consuming `/api/long-exposure/v1/*`
- [ ] Register project entry in `src/App.tsx` under `~/workspace/long-exposure`
- [ ] (Optional) `LongExposureContext` if streaming is needed — likely not, daily updates

In this repo:

- [ ] Production bring-up on luv (apply Caddyfile entries from @../deploy/INFRA-NOTES.md, fill `.env`, `docker compose up -d`)
- [ ] Backfill the previous 30 days end-to-end so launch day already has narrated history
- [ ] Public launch verification: `curl https://vedanta.systems/api/long-exposure/v1/health`

---

## Phase 2 — DEEP+ / DPLS (targeting ~3 weeks post-launch)

Not a vague future — a concrete follow-up sprint. Rationale and trade-offs in @decisions.md (2026-05-10 entry).

**Why DEEP+ and not DEEP**: information-theoretic superset (DPLS ⊃ DEEP ⊃ TOPS). Once we've built any kind of book-state machine, the marginal cost to go from price-level book to order-by-order is small. DEEP is a stopping point we'd throw away.

**Why DEEP+ as a separate phase rather than v1**: validation is qualitatively harder (book-state reconstruction at sampled timestamps + per-order lifecycle correctness, with no "compare to a published authoritative number" check). Spec is recent (Jan 2025) and **no open-source DEEP+ parser exists in any language** — we'd be flying solo on decoder correctness without a community parser to diff against. Building TOPS first matures the shared infrastructure (transport, admin decoders, Postgres writer, Temporal pipeline, LLM prompts) so the DEEP+ sprint can focus purely on the new work.

### What phase 2 adds

- [ ] DEEP+ trading-message decoders (`a` Add, `M` Modify, `R` Delete, `L` Order Executed, `T` Trade, `B` Trade Break, `C` Clear Book — see @protocol-notes.md for byte-level layouts)
- [ ] **Order book state machine**: `Map<OrderID → {symbol, side, price, size}>` per session, updated on Add/Modify/Delete/Execute
- [ ] New events table for order-level events (or extend `events` with order-lifecycle columns; design tbd)
- [ ] Book-state validation: snapshot the reconstructed book at end-of-day, compare to derived TOPS BBO from the same date's TOPS file (independent feed) — if BBO drift exceeds threshold, parser bug
- [ ] DEEP+ narrative templates: order-lifecycle stories ("8 orders posted+cancelled within 50ms", "median order time-in-book on SPY collapsed from 800ms to 90ms")
- [ ] Update vedanta-systems' `long-exposure-browser.tsx` to surface DEEP+ narratives

### What phase 2 reuses (no rewrite)

- IEX-TP transport layer (`com.longexposure.transport.*`)
- All 7 admin decoders (`com.longexposure.admin.*`) — byte-identical across feeds
- `DownloadHistActivity` (parameterized on `feed_name` from day 1)
- Postgres writer skeleton + TimescaleDB hypertable conventions
- Scoring pipeline (event-type-agnostic)
- LLM narration loop + caching by event hash
- Temporal workflow scaffolding (parameterized on feed)
- vedanta-systems integration (just adds new narrative types)
- Caddy routes, env setup, observability

Rough estimate: 2–3 weeks for a focused DEEP+ sprint, mostly spent on the order book state machine + validation harness for it.

### Open question for phase 2

- **History depth**: DPLS is only available in HIST from Jan 2025 forward (~16 months). 30-day baseline window is fine. But if we ever want to do multi-year analysis, that has to stay on TOPS+DEEP.
