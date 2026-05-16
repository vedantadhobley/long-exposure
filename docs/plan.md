# Build Plan

The schedule for going from "scaffolded repo" to "live narrated public daily-market feed surfaced inside vedanta.systems."

Status legend: `[ ]` not started, `[~]` in progress, `[x]` complete.

This plan is the agent-editable source of truth. The README has a condensed prose version; if they conflict, this file wins.

---

## ⏯ Where we are right now (2026-05-11 late session)

**Pivot recorded 2026-05-11 (morning)**: v1 is DEEP+/DPLS, not TOPS. TOPS code repurposed as validation oracle. Full rationale in `docs/decisions.md`.

**Session progress (late 2026-05-11)**:

- ✅ **Sprint A done** — DEEP+ parser: 7 trading-message records, `DplsMessage` sealed interface, `DplsMessageRouter`, `SaleConditionFlags` promoted to shared `wire/` package. 14 new JUnit tests; 45 total passing across the suite.
- ✅ **Trade-level cross-validation done** (Sprint D, first half) — Main.java auto-detects feed from IEX-TP protocol id; aggregates per-symbol volume; queries Postgres for TOPS aggregates and diffs. Full 10.4 GB DPLS file decoded in 97 s (~3.75M msg/sec, 364,098,518 messages). **9,134 / 9,134 symbols match exactly, 0 mismatches, 0 share delta** vs the existing TOPS data already loaded for 2026-05-08. Every shared admin message type also matches count-for-count between feeds.
- 🔜 **Sprint B next** — Order book state machine. `Map<orderId, OrderState>` per symbol, updated on Add/Modify/Delete/Execute, dropped on ClearBook. Derives top-of-book at arbitrary timestamps. Then Sprint D's second half: diff that derived BBO against the loaded TOPS QuoteUpdate stream.

Concrete next actions in order:

1. **`com.longexposure.dpls.OrderBook` class** — instance per symbol, internal `Map<Long, OrderState>`. Methods: `add(AddOrder)`, `modify(OrderModify)`, `execute(OrderExecuted)`, `delete(OrderDelete)`, `clear()`. Plus derivation: `bestBid()`, `bestAsk()`, `depthAtLevel(int)`, `orderCount()`.
2. **`OrderBookManager` (or similar)** — holds `Map<String symbol, OrderBook>`, routes incoming DEEP+ trading messages to the right book. Handles ClearBook by dropping the symbol's entry.
3. **JUnit tests** — small synthetic message streams (5–10 events per test) covering: simple add+execute, partial fills, modify with priority maintained vs reset, delete, clear-book, add-after-delete-with-same-id.
4. **BBO cross-validation against loaded TOPS data** — run DPLS through the parser + book manager; at each timestamp where a TOPS QuoteUpdate exists in DB for the same symbol, derive BBO from book state and diff. Tolerance considerations: order events and quote events in DEEP+ vs TOPS may arrive in different packets, so exact-microsecond comparison may need a small alignment window (~100 ns).

**Expected pace for Sprint B**: ~150–250 LOC + tests. Comparable to one of yesterday's TOPS sub-sprints.

## Days 1–3 — Foundation ✅ done

- [x] Set up Gradle Kotlin DSL project (`build.gradle.kts`, `settings.gradle.kts`)
- [x] Multi-stage Dockerfile (gradle JDK21 builder → JRE alpine; no libpcap — pcap reader is pure Java)
- [x] Open the GitHub repo public on Day 1 (MIT licensed)
- [x] Implement `pcap.PcapReader` — pure Java pcap-ng + libpcap reader streaming from .pcap.gz, no pcap4j dep
- [x] Implement `transport.IexTpDecoder` — 40-byte IEX-TP header, all fields, protocol-name lookup for TOPS/DEEP/DEEP+
- [x] Get raw IEX-TP headers + message bodies printing to stdout from a real HIST file (verified end-to-end on TOPS and DEEP+)

## Days 4–7 — TOPS parser + validation harness (in parallel)

- [x] Implement `tops.TopsMessageRouter` — dispatches admin first, then TOPS-specific
- [x] Admin decoders in `com.longexposure.admin.*` (shared across all feeds for phase 2):
  - [x] System Event (`S`, 10 B)
  - [x] Security Directory (`D`, 31 B)
  - [x] Trading Status (`H`, 22 B)
  - [x] Retail Liquidity Indicator (`I`, 18 B)
  - [x] Operational Halt Status (`O`, 18 B)
  - [x] Short Sale Price Test Status (`P`, 19 B)
  - [x] Security Event (`E`, 18 B — DEEP/DEEP+ only, byte-identical)
- [x] TOPS-specific trading decoders in `com.longexposure.tops.*`:
  - [x] Quote Update (`Q`, 42 B) — with halted/off-hours flag helpers
  - [x] Trade Report (`T`, 38 B) — with Sale Condition Flags helper (5 bits, eligibility derived)
  - [x] Trade Break (`B`, 38 B) — same shape as Trade Report
  - [x] Official Price (`X`, 26 B) — Opening / Closing enum
  - [x] Auction Information (`A`, 80 B) — 5 auction types, imbalance side, collars, scheduled match
- [x] Shared wire helpers in `com.longexposure.wire.Bytes` (LE reads + symbol/ASCII decoding)
- [x] Common `IexMessage` marker (non-sealed) so router can return one type; `AdminMessage` + `TopsMessage` sealed sub-hierarchies
- [x] 48 JUnit tests against spec worked-example bytes, all passing
- [x] **End-to-end smoke test**: 9.5 GB real HIST file from 2026-05-08 → 294,790,405 messages decoded in 1m39s. Histogram: 97% Quote Updates, 7.7M Trade Reports, 2K Auction Information, 6 System Events. Round-trip works.
- [ ] **`validation.DailyTotalsValidator`** — cross-checks against IEX's published per-symbol summaries. Don't wait until the end; every new decoder gets a paired check. Still pending — needs the `/stats` endpoint figured out.
- [ ] **Reference-implementation cross-check**: integrate `WojciechZankowski/iextrading4j-hist` as a test dependency (Java, same JVM); for each sample TOPS .pcap.gz, run both parsers and diff message streams. Any divergence = bug (usually ours). See @protocol-notes.md "Reference implementations" section.

**Shared decoder note delivered**: 7 of these messages (`S`, `D`, `H`, `I`, `O`, `P`, plus `E` Security Event which is DEEP/DEEP+ only) are byte-identical across all three feeds. Package layout matches: `com.longexposure.admin.*` for shared, `com.longexposure.tops.*` for TOPS trading, `com.longexposure.dpls.*` to come in phase 2. ~60% of the decoder LOC is in the admin package, all reusable as-is.

## Days 8–10 — Postgres + TimescaleDB + storage

- [x] Write `parser/src/main/resources/schema.sql` — per-message-type hypertables (trades / trade_breaks / quotes / status_events / auction_info / official_prices / securities / retail_liquidity), narratives table keyed by event_hash, pipeline_runs for Temporal bookkeeping. Every event table stores both TIMESTAMPTZ (hypertable partition) and BIGINT nanos (preserves spec precision). `feed_source` column on every event row so phase-2 DEEP+ data lands alongside without schema churn.
- [x] Continuous aggregate `daily_volume_by_symbol` plus a `symbol_baseline_30d` view doing the rolling 30-trading-day window — feeds the scorer's "is this event unusual?" comparisons.
- [x] `SchemaManager.apply()` — JDBC, reads schema.sql from classpath, splits on `;`, executes idempotently (every DDL uses `IF NOT EXISTS` / `if_not_exists => TRUE`).
- [x] `TimescaleWriter` — COPY-based bulk insert, one StringBuilder buffer per table, auto-flushes at 100K rows. ~50–200K rows/sec; row-by-row INSERT can't keep up with 300M messages/day.
- [x] Wire into `Main.java` smoke test, gated by `IEX_WRITE_DB=true` env var.
- [x] End-to-end run: 9.5 GB 2026-05-08 HIST → Postgres, **22:27 minutes**. All 294,790,405 messages written. Counts match parser histogram exactly across every table. Continuous aggregate refreshed and confirmed: 9,134 symbols traded, 770M shares total. Throughput ~219K rows/sec via COPY. Spot-checks in `docs/sql/spot-check.sql` confirm sensible top-volume tickers (TZA, BITO, NVD, NVDA, INTC, ...), 105 trading halts, 922 Reg SHO triggers, exact 6 SystemEvents bracketing the session.

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

- [x] `src/server/routes/long-exposure.ts` — Express router connecting to `long-exposure-{dev,prod}-postgres` over the `luv-{dev,prod}` shared docker network. Read-only endpoints: `/health`, `/latest`, `/dates`, `/day/:date`, `/symbol/:symbol`, `/event/:id`. No separate API container in this repo (unlike spin-cycle, which has its own FastAPI because users submit work — long-exposure has no inbound surface).
- [x] `src/types/long-exposure.ts` — response shapes.
- [x] `src/components/long-exposure-browser.tsx` — minimal v1 (grouped list by scorer). Polished timeline UI is future work.
- [x] Register project entry in `src/App.tsx` under `~/workspace/long-exposure`.
- [ ] Polished UI (drill-down panel showing blueprint + breakdown JSON; ticker search; date picker).

In this repo:

- [ ] Production bring-up on luv (apply Caddyfile entries from @../deploy/INFRA-NOTES.md, fill `.env`, `docker compose up -d`)
- [ ] Backfill the previous 30 days end-to-end so launch day already has narrated history
- [ ] Public launch verification: `curl https://vedanta.systems/api/long-exposure/v1/health`

---

## ✨ DEEP+ v1 sprints (was "phase 2", now the main path)

Reframed 2026-05-11 — see `docs/decisions.md`. The TOPS work done today is repurposed as the validation oracle, not deferred to v2.

### Sprint A — DEEP+ parser ✅ done 2026-05-11

- [x] Download 2026-05-08 DPLS file (10.4 GB at `~/workspace/data/long-exposure/raw/20260508_IEXTP1_DPLS1.0.pcap.gz`)
- [x] `com.longexposure.dpls.DplsMessage` sealed interface (mirrors TopsMessage)
- [x] DEEP+ trading-message records (all 7: `a` Add, `M` Modify, `R` Delete, `L` Order Executed, `T` Trade, `B` Trade Break, `C` Clear Book)
- [x] `DplsMessageRouter` (admin dispatch first, then DEEP+ trading)
- [x] `SaleConditionFlags` promoted from `tops/` to `wire/` (shared across feeds per Appendix A)
- [x] 14 JUnit tests against spec worked examples; 45 total passing

### Sprint B — Order book state machine 🔜 next

- [ ] `OrderBook` class — `Map<Long orderId, OrderState>` per symbol
- [ ] Update on Add/Modify/Delete/Execute messages
- [ ] Drop entries on ClearBook
- [ ] Derive top-of-book (BBO) at arbitrary timestamps
- [ ] Compute aggregate book metrics (best-price size, depth at level)
- [ ] `OrderBookManager` per symbol; route messages from the parser stream

### Sprint C — Storage extensions ✅ done 2026-05-12

- [x] 5 new hypertables in `schema.sql`: `orders_add`, `orders_modify`, `orders_delete`, `orders_executed`, `clear_books`. DPLS Trade/TradeBreak share existing `trades`/`trade_breaks` via `feed_source='DPLS'`.
- [x] `TimescaleWriter` extensions: 5 new Table enum entries, 7 new appenders, `IexMessage → DplsMessage` dispatch in `writeMessage()`.
- [x] End-to-end run on 2026-05-08 DPLS: 364,098,518 rows written in 35:07 (~174 K rows/sec), zero loss, per-type counts match the parser histogram exactly.
- [x] **Cross-feed trade-volume validation in SQL**: 9,134 / 9,134 symbols match, 0 share delta — reproducible from loaded DB, not just in-memory parser run.

### Sprint D — Cross-validation against TOPS ✅ done 2026-05-11/12

- [x] **Trade-level**: SUM(OrderExecuted.size + Trade.size) per symbol from DPLS == SUM(TradeReport.size) per symbol from TOPS. 9,134/9,134 symbols match exactly, 0 mismatches, 0 share delta.
- [x] **BBO-level**: per-symbol BBO derived from DPLS book state vs TOPS Quote Update BBO. **99.4184 % match across 2 trading days**; full analysis + residual root cause in @validation-results.md.
- [x] **Triangle leg added**: DPLS↔DEEP price-level cross-check (TOPS-independent). 100.0000 % on both days. The load-bearing correctness claim.

### What's reused unchanged from today

- IEX-TP transport (`com.longexposure.transport.*`)
- All 7 admin decoders (`com.longexposure.admin.*`)
- pcap-ng reader (`com.longexposure.pcap.*`)
- Wire helpers (`com.longexposure.wire.*`)
- `SchemaManager` + base schema + continuous aggregates
- `TimescaleWriter` shape + COPY-based bulk insert pattern
- Docker Compose dev/prod stacks + Caddy routes + observability

### Known open questions

- ~~**Schema design**: order-level events as their own `orders` hypertable, or extended `events`?~~ → Resolved Sprint C: separate per-event-type tables (`orders_add`/`orders_modify`/`orders_delete`/`orders_executed`/`clear_books`), matching the existing TOPS-side pattern.
- **History depth**: DPLS in HIST only goes back to Jan 2025 (~16 months). 30-day baselines + last-N-day narrations are fine. Multi-year historical analysis would require TOPS+DEEP fallback.
- **Book state validation**: cross-checking against TOPS works for the BBO-derivable subset. Deep-book metrics (depth at level 2+) have no external reference; we'd accept "internally consistent" as the bar for those.

---

## After DEEP+ ships — Days 11+ (renumbered)

The remaining post-parser work, in priority order. Day numbers are nominal now; pace will dictate real cadence.

### Days 11–13 (post-DEEP+) — Baseline data + bootstrap

- [ ] One-time bootstrap: download + parse the last 30 trading days of DPLS HIST data
- [ ] Re-run the scorer + narrator over those 30 backfilled days to produce the visible launch archive
- [ ] Spot-check baselines (real halt should score high; routine quote should score low)

### Days 14–17 (post-DEEP+) — Event scoring

Design reference: @scoring-and-narration.md — pattern catalog (spoof-shaped post-cancel clusters, liquidity withdrawal, layering, sweeps, iceberg detection, time-in-book distribution shifts).

- [ ] `EventScorer` interface (input: event + symbol baseline; output: score + JSON breakdown)
- [ ] Per-pattern scorers — one class per pattern in @scoring-and-narration.md plus the simpler ones (halt, large trade, quote-spread anomaly)
- [ ] Score breakdown JSON must be sufficient to reconstruct every claim in the eventual narrative (grounding contract)
- [ ] Tune weights until top-N events on 2026-05-08 pass the "would I read this?" check

### Days 18–19 — LLM narration

**The hardest design work in the project.** Design reference: @scoring-and-narration.md "Narration design principles" + "Output structure" sections.

- [ ] Prompt template that takes a structured scored event and produces 2–3 sentence narration
- [ ] Automated grounding check: every number / claim in the output text must appear in the structured input
- [ ] Per-day "daily patterns" second-pass narration consuming the full scored event list
- [ ] Cache by event hash (already supported by `narratives` table schema)
- [ ] Refuse on incomplete data
- [ ] Tone: financial-journalist register, no jargon, no excited-blog tone

### Days 20–21 — Temporal + API

- Convert the smoke-test loop in `Main.java` into a Temporal workflow with proper retry policies, heartbeating, replayability.
- Wire FastAPI endpoints listed in README.

### Day 22 — vedanta-systems integration + deploy

- nginx `/api/long-exposure/*` → API container
- `src/components/long-exposure-browser.tsx` in vedanta-systems
- Production bring-up on luv, public launch with 30-day backfilled archive
