# TODO

Project scratchpad, agent + human editable. Cross-references @plan.md for full context and @decisions.md for the reasoning behind structural choices.

Last refresh: 2026-05-13. Sprint 1 (Temporal scaffolding) done. **Bootstrap baselines + event scoring next.**

---

## ⏯ Start of next session — paste-ready checklist

State of the world:

- TOPS + DEEP + DPLS .pcap.gz for both 2026-05-07 and 2026-05-08 sit at `~/workspace/data/long-exposure/raw/`.
- Both days fully loaded into Postgres for triangle validation purposes.
- Triangle cross-validation done on both days (DPLS↔DEEP ≥ 99.99999 %, DPLS→TOPS 99.4017–99.4184 %, DEEP→TOPS identical to DPLS→TOPS to the share).
- `prior_close_20260507.csv` derived and used for per-symbol round-lot tier.
- **Temporal pipeline running**. `DailyPipelineWorkflow` + `ValidateOnlyWorkflow` registered on task queue `long-exposure-daily-pipeline`. Cron schedule paused — operator unpauses when ready.
- `validation_runs` populated for 2026-05-08 with `status=passed`.

Next thing to build: **Bootstrap baselines** (30 trading days of historical HIST → load via the workflow → cagg populates → ready for scorer). Trivially scriptable as a loop of `temporal workflow start` calls.

After bootstrap, the algorithmic core: **EventScorer** (per-event-type scoring with transparent JSON breakdowns — pattern catalog in @scoring-and-narration.md).

1. **Verify the dev stack is still up**:

   ```bash
   docker compose -f docker-compose.dev.yml ps
   ```

   Temporal containers (`long-exposure-dev-temporal`, `long-exposure-dev-temporal-postgres`, `long-exposure-dev-temporal-ui`) should all be healthy. Temporal UI is at `http://long-exposure-dev-temporal-ui.luv`.

2. **Re-confirm latest commits**:

   ```bash
   git log --oneline -10
   ```

3. **Trigger an ad-hoc workflow** for any historical date to verify the pipeline still works end-to-end before kicking off bootstrap:

   ```bash
   docker exec long-exposure-dev-temporal temporal workflow start \
     --task-queue long-exposure-daily-pipeline \
     --type DailyPipelineWorkflow \
     --workflow-id daily-pipeline-test-YYYYMMDD \
     --input '{"targetDate":[YYYY,M,D],"pollUntilReady":false,"forceReingest":false,"runRetentionSweep":false}'
   ```

4. **Then start on bootstrap** — script a loop that calls the above with `forceReingest=true` for the previous 30 trading days. Use the `validate-only` workflow shape (just `Iface`) for any reruns of just-validation.

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

Full details in @plan.md.

- [x] **Temporal scaffolding** ✅ done 2026-05-13. Two workflows + 10 activities + paused cron schedule. End-to-end verified on 2026-05-08. Full layout in @temporal-design.md.
- [ ] **Bootstrap baselines** — run the workflow 30 times against historical HIST dates → continuous aggregate populates → ready for scorer. (Trivially scriptable now that the workflow is in.)
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
