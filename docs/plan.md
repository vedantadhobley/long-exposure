# Build Plan

The schedule for going from "scaffolded repo" to "live narrated public daily-market feed surfaced inside vedanta.systems."

Status legend: `[ ]` not started, `[~]` in progress, `[x]` complete.

This plan is the agent-editable source of truth. The README has a condensed prose version; if they conflict, this file wins.

---

## ⏯ Next session — start here

**Pivot recorded 2026-05-11**: v1 is now **DEEP+ / DPLS**, not TOPS. TOPS code is repurposed as the validation oracle. Full rationale in `docs/decisions.md` (2026-05-11 entry).

Concrete actions to start the next session, in order:

1. **Download a 2026-05-08 DPLS .pcap.gz** (same trading date as the TOPS file we already have, so cross-validation is symbol-for-symbol on the same day):

   ```bash
   curl -s 'https://iextrading.com/api/1.0/hist?date=20260508' \
     | jq '.[] | select(.feed == "DPLS")'
   # copy the link, then:
   curl -L -o ~/workspace/data/long-exposure/raw/20260508_IEXTP1_DPLS1.0.pcap.gz '<link>'
   ```

2. **Create the `com.longexposure.deepplus` package** mirroring `com.longexposure.tops`:
   - Sealed `DeepPlusMessage extends IexMessage` permitting the 7 trading-message records
   - `DeepPlusMessageRouter` (tries `AdminMessages` first, falls back to DEEP+ trading)
   - Records (with spec page numbers; see `deep-plus-1.02.pdf` at `~/workspace/data/long-exposure/specs/`):
     - `AddOrder` (`a`, 0x61, 38 B — Side / OrderID / Size / Price)
     - `OrderModify` (`M`, 0x4D, 38 B — Modify Flags bit 0 = priority maintained)
     - `OrderDelete` (`R`, 0x52, 26 B — references OrderID)
     - `OrderExecuted` (`L`, 0x4C, 46 B — references OrderID, carries TradeID)
     - `Trade` (`T`, 0x54, 38 B — non-displayed × non-displayed; doesn't modify book)
     - `TradeBreak` (`B`, 0x42, TBD bytes — same shape as TOPS TradeBreak)
     - `ClearBook` (`C`, 0x43, TBD bytes — drop all orders for symbol)

3. **JUnit tests** for each decoder using spec worked examples (pages 16–22 of `deep-plus-1.02.pdf`).

4. **Order book state machine** — `Map<OrderID, OrderState>` per session. Update on Add/Modify/Delete/Execute. Drop on ClearBook. The hardest single piece in the project until LLM integration.

5. **Schema extensions** — add `orders` hypertable (Add Order rows) + extend writer.

6. **End-to-end run** + cross-validate against the existing TOPS data:
   - Derive per-second BBO from DEEP+ book state, compare to actual TOPS Quote Updates → must match
   - Sum DEEP+ Order Executed sizes per symbol, compare to TOPS Trade Report totals → must match

7. **Then**: scoring (`com.longexposure.scoring.EventScorer`) against DEEP+ event types, designed from day 1 for order-lifecycle narratives.

**Expected pace**: comparable to today's TOPS sprint. ~60% of the parser stack is feed-agnostic and already done. Net-new work is 7 decoders + book state machine + writer extensions + cross-validation.

**Hardest part of the project is still ahead**: not the parser (this is mechanical), but the **LLM narration prompt engineering + scoring algorithm tuning**. Days 14–19 in the original plan are where the real product-design uncertainty lives.

## Days 1–3 — Foundation

- [x] Set up Gradle Kotlin DSL project (`build.gradle.kts`, `settings.gradle.kts`)
- [x] Multi-stage Dockerfile (gradle JDK21 builder → JRE alpine + libpcap)
- [x] Open the GitHub repo public on Day 1 (MIT licensed)
- [ ] Implement `pcap.PcapReader` using pcap4j — open a `.pcap.gz` and stream packets
- [ ] Implement `transport.IexTpDecoder` — parse the 40-byte IEX-TP header
- [ ] Get raw TOPS message bytes printing to stdout from a real HIST file

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

**Shared decoder note delivered**: 7 of these messages (`S`, `D`, `H`, `I`, `O`, `P`, plus `E` Security Event which is DEEP/DEEP+ only) are byte-identical across all three feeds. Package layout matches: `com.longexposure.admin.*` for shared, `com.longexposure.tops.*` for TOPS trading, `com.longexposure.deepplus.*` to come in phase 2. ~60% of the decoder LOC is in the admin package, all reusable as-is.

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

- [ ] nginx location for `/api/long-exposure/*` → `long-exposure-prod-api:3001`
- [ ] `src/components/long-exposure-browser.tsx` — timeline UI consuming `/api/long-exposure/v1/*`
- [ ] Register project entry in `src/App.tsx` under `~/workspace/long-exposure`
- [ ] (Optional) `LongExposureContext` if streaming is needed — likely not, daily updates

In this repo:

- [ ] Production bring-up on luv (apply Caddyfile entries from @../deploy/INFRA-NOTES.md, fill `.env`, `docker compose up -d`)
- [ ] Backfill the previous 30 days end-to-end so launch day already has narrated history
- [ ] Public launch verification: `curl https://vedanta.systems/api/long-exposure/v1/health`

---

## ✨ DEEP+ v1 sprints (was "phase 2", now the main path)

Reframed 2026-05-11 — see `docs/decisions.md`. The TOPS work done today is repurposed as the validation oracle, not deferred to v2.

### Sprint A — DEEP+ parser

- [ ] Download 2026-05-08 DPLS file (same date as TOPS data already loaded)
- [ ] `com.longexposure.deepplus.DeepPlusMessage` sealed interface (mirrors TopsMessage)
- [ ] DEEP+ trading-message records (`a` Add, `M` Modify, `R` Delete, `L` Order Executed, `T` Trade, `B` Trade Break, `C` Clear Book)
- [ ] `DeepPlusMessageRouter` (admin dispatch first, then DEEP+ trading)
- [ ] JUnit tests against spec worked examples (`deep-plus-1.02.pdf` pages 16–22)

### Sprint B — Order book state machine

- [ ] `OrderBook` class — `Map<Long orderId, OrderState>` per symbol
- [ ] Update on Add/Modify/Delete/Execute messages
- [ ] Drop entries on ClearBook
- [ ] Derive top-of-book (BBO) at arbitrary timestamps
- [ ] Compute aggregate book metrics (best-price size, depth at level)

### Sprint C — Storage extensions

- [ ] New `orders` hypertable in `schema.sql` (or extended events table — TBD by design)
- [ ] `TimescaleWriter` extensions for DEEP+ message types
- [ ] End-to-end run: DEEP+ .pcap.gz → Postgres

### Sprint D — Cross-validation against TOPS

- [ ] Per-second BBO derived from DEEP+ book state == TOPS Quote Update BBO for the same symbol/second
- [ ] Sum of DEEP+ Order Executed sizes per symbol == sum of TOPS Trade Report sizes per symbol
- [ ] Any divergence is a DEEP+ decoder bug (TOPS is the reference, since we've already validated TOPS against spec example bytes + the parser's per-type histogram)

### What's reused unchanged from today

- IEX-TP transport (`com.longexposure.transport.*`)
- All 7 admin decoders (`com.longexposure.admin.*`)
- pcap-ng reader (`com.longexposure.pcap.*`)
- Wire helpers (`com.longexposure.wire.*`)
- `SchemaManager` + base schema + continuous aggregates
- `TimescaleWriter` shape + COPY-based bulk insert pattern
- Docker Compose dev/prod stacks + Caddy routes + observability

### Known open questions

- **Schema design**: order-level events as their own `orders` hypertable, or extended `events`? Best decided after Sprint A so we can see the actual data volume distribution.
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

- [ ] `EventScorer` interface (input: event + symbol baseline; output: score + JSON breakdown)
- [ ] Per-event-type scorers — halt, large trade, quote-spread anomaly, order-flow anomaly (order add/cancel rates, time-in-book distribution)
- [ ] Tune weights until top-N events on 2026-05-08 pass the "would I read this?" check

### Days 18–19 — LLM narration

**The hardest design work in the project.** Prompt engineering against `llama-large.joi` until narratives read like a sober finance journalist. Cache by event hash. Refuse on incomplete data. Tone: clear, factual, accessible.

### Days 20–21 — Temporal + API

- Convert the smoke-test loop in `Main.java` into a Temporal workflow with proper retry policies, heartbeating, replayability.
- Wire FastAPI endpoints listed in README.

### Day 22 — vedanta-systems integration + deploy

- nginx `/api/long-exposure/*` → API container
- `src/components/long-exposure-browser.tsx` in vedanta-systems
- Production bring-up on luv, public launch with 30-day backfilled archive
