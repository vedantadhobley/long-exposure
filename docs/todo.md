# TODO

Project scratchpad, agent + human editable. Cross-references @plan.md for full context and @decisions.md for the reasoning behind structural choices.

Last refresh: 2026-05-12. Sprints A–D done. **Temporal scaffolding is next.**

---

## ⏯ Start of next session — paste-ready checklist

State of the world:

- TOPS + DEEP + DPLS .pcap.gz for both 2026-05-07 and 2026-05-08 sit at `~/workspace/data/long-exposure/raw/`.
- Both days fully loaded into Postgres (TOPS: 295 M rows + DPLS: 364 M rows for 2026-05-08; same shape for 2026-05-07).
- Triangle cross-validation done on both days (DPLS↔DEEP 100.0000 %, DPLS→TOPS 99.4184 %, DEEP→TOPS 99.4184 %). See @validation-results.md.
- `prior_close_20260507.csv` derived and used for per-symbol round-lot tier.
- `parser` Java module is fully wired through `Main` for: smoke-test, write-db, BBO cross-validation, prior-close extract, and trace tools.

Next thing to build: **Temporal scaffolding** — wraps the existing parse-and-write loop into a Temporal workflow with retries + heartbeating + cron schedule, so the daily HIST file gets ingested automatically.

1. **Verify the dev stack is still up**:

   ```bash
   docker compose -f docker-compose.dev.yml ps
   ```

   Temporal containers (`long-exposure-dev-temporal`, `long-exposure-dev-temporal-postgres`, `long-exposure-dev-temporal-ui`) should all be healthy. Temporal UI is at `http://long-exposure-dev-temporal-ui.luv`.

2. **Re-confirm latest commits** to know where we resume:

   ```bash
   git log --oneline -10
   ```

3. **Tell me "go" and I'll start on Temporal Sprint 1** — minimal workflow + activity registration: `WorkerMain` (replaces the current smoke-test `Main` loop), `IngestWorkflow`, three activity classes (`DownloadHistActivity`, `DecompressActivity`, `ParseAndWriteActivity`), retry policies per activity, heartbeats from the long-running parse, cron-scheduled at 6 AM ET. The 30-day baseline bootstrap and downstream activities (validation, scoring, narration, storage) layer on top later.

---

## DEEP+ v1 sprint work

Full layout in @plan.md. Quick status:

### Sprint A — parser ✅ done 2026-05-11

- [x] `com.longexposure.dpls` package
- [x] Sealed `DplsMessage` interface
- [x] 7 trading-message records: AddOrder (a), OrderModify (M), OrderDelete (R), OrderExecuted (L), Trade (T), TradeBreak (B), ClearBook (C)
- [x] `DplsMessageRouter`
- [x] `SaleConditionFlags` promoted from `tops/` to `wire/`
- [x] 14 JUnit tests (45 total in the suite)

### Sprint B — order book state machine 🔜 next

- [ ] `OrderBook` class: `Map<Long orderId, OrderState>` per symbol
- [ ] Updates on Add/Modify/Delete/Execute
- [ ] ClearBook drops all entries
- [ ] BBO derivation method (`bestBid()`, `bestAsk()`)
- [ ] Aggregate depth metrics (size-at-best, depth at level N)
- [ ] `OrderBookManager` — `Map<symbol, OrderBook>` + dispatch
- [ ] JUnit tests with synthetic 5–10-event message streams

### Sprint B — order book state machine ✅ done 2026-05-11

- [x] `OrderBook` class: `Map<Long orderId, OrderState>` per symbol
- [x] Updates on Add/Modify/Delete/Execute
- [x] ClearBook drops all entries
- [x] BBO derivation method (`bestBidProtected()`, `bestAskProtected()`)
- [x] Aggregate depth metrics (`aggregateAt(side, priceRaw)`, etc.)
- [x] `OrderBookManager` — `Map<symbol, OrderBook>` + dispatch
- [x] JUnit tests with synthetic 5–10-event message streams

### Sprint C — storage extensions ✅ done 2026-05-12

- [x] 5 new hypertables in `schema.sql`: `orders_add`/`orders_modify`/`orders_delete`/`orders_executed`/`clear_books`. DPLS Trade and TradeBreak reuse the existing `trades`/`trade_breaks` tables via `feed_source='DPLS'`.
- [x] `TimescaleWriter` extensions: 5 new Table enum entries, 7 new appenders, `IexMessage → DplsMessage` dispatch.
- [x] End-to-end run on 2026-05-08 DPLS: 364,098,518 rows written in 35:07 (~174 K rows/sec), zero loss.

### Sprint D — cross-validation against TOPS ✅ done 2026-05-11/12

- [x] **Trade-level done 2026-05-11**: SUM(OrderExecuted.size + Trade.size) per symbol from DPLS == SUM(TradeReport.size) per symbol from TOPS. 9,134/9,134 symbols matched exactly, 0 share delta across 770 M total shares. Now also reproducible from loaded DB via SQL (post-Sprint C).
- [x] **BBO-level done 2026-05-11/12**: per-symbol BBO derived from DPLS book == TOPS Quote Update BBO at 99.4184 % across both validated days. Residual fully analyzed in @validation-results.md.
- [x] **Triangle leg done 2026-05-11/12**: DPLS↔DEEP price-level (TOPS-independent) at 100.0000 % on both days. The load-bearing correctness claim.

---

## After parser+storage ships — order of next work

Pulled Temporal earlier than the original plan had it; once orchestration is in, the downstream activities slot in one by one. Full details in @plan.md.

- [ ] **Temporal scaffolding** — convert the smoke-test loop into a Temporal workflow with retries + heartbeats. Three activities to start: `DownloadHistActivity`, `DecompressActivity`, `ParseAndWriteActivity`. Cron-scheduled nightly. Tomorrow's session.
- [ ] **Bootstrap baselines** — run the workflow 30 times against historical HIST dates → continuous aggregate populates → ready for scorer. (Trivially scriptable once the workflow is in.)
- [ ] **Event scoring** (`com.longexposure.scoring.EventScorer`) — the algorithmic core. Per-event-type scoring with transparent JSON breakdown for "why this event scored high"
- [ ] **LLM narration** — prompt engineering against `llama-large.joi`. Expected to be the hardest part of the project.
- [ ] **FastAPI endpoints** — the read-only API consumed by vedanta-systems
- [ ] **vedanta-systems integration** — `/api/long-exposure/*` nginx route + `long-exposure-browser.tsx` component
- [ ] **Production bring-up** on luv (Caddyfile entries from `deploy/INFRA-NOTES.md`)
- [ ] **Public launch** with 30-day backfilled narrative archive

---

## TOPS + DEEP work — repurposed as validation oracles (kept, not deleted)

Both TOPS and DEEP code stay in the repo and earn their keep on every validation run:

- TOPS decoders (`com.longexposure.tops.*`) — TOPS QU is the BBO reference oracle for `DplsBboCrossValidator` / `DeepBboCrossValidator`. TOPS Trade is the trade-aggregate reference oracle. Loaded into Postgres for both 2026-05-07 and 2026-05-08.
- DEEP decoders (`com.longexposure.deep.*`) — DEEP's price-level book is the TOPS-independent reference for `DeepVsDplsValidator`. Produces the 100.0000 % triangle leg.
- `TopsMessageRouter`, `DeepMessageRouter` — both exercised in the validators. Pipeline-side, only DPLS is ingested for the product itself.

---

## Deferred / phase 2 (post-launch)

- [ ] Real-time streaming (requires the IEX SIP feed; different licensing model)
- [ ] Multi-exchange comparison
- [ ] User accounts / saved searches / alerts
- [ ] DEEP / TOPS-only historical analysis for pre-2025 dates (DPLS history doesn't go back that far)

---

## Bug-catching infra (low-but-real priority)

- [ ] **`DailyTotalsValidator`** — cross-check parsed trade volumes/counts against IEX's published per-symbol daily summaries. Works for all three feeds. Still 0 LOC written. Needs the `/stats` endpoint shape figured out. The DPLS-side trade aggregate already cross-validates against TOPS perfectly (9134/9134, 0 share delta), so this is now belt-and-suspenders rather than a primary check.
- [ ] **Reference-parser diff (TOPS only)** — wire `WojciechZankowski/iextrading4j-hist` as a test dep, diff message streams against ours for a sample TOPS file. Lower priority now that DPLS↔DEEP-at-100 % confirms decoder correctness across the wire-format boundary.

---

## Cleanup / done-today notes

- [x] ~~Frontend scaffold removed~~ (2026-05-10)
- [x] ~~Caddy `caddy.d/` per-project split~~ (2026-05-10, on the proxy side)
- [x] ~~Day 1–10 implementation~~ (2026-05-11)
- [x] ~~End-to-end 9.5 GB → 295M Postgres rows~~ (2026-05-11, 22:27 min)
