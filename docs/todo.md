# TODO

Project scratchpad, agent + human editable. Cross-references @plan.md for full context and @decisions.md for the reasoning behind structural choices.

Last refresh: 2026-05-11 late session. Sprint A and trade-level cross-validation done; Sprint B is next.

---

## ⏯ Start of next session — paste-ready checklist

Both data files (TOPS + DPLS) are already at `~/workspace/data/long-exposure/raw/`. TOPS is fully loaded into Postgres for 2026-05-08; DEEP+ trade aggregates have been cross-validated against it (0 mismatches across all 9,134 symbols).

Next thing to build: **Sprint B — order book state machine**.

1. **Verify the dev stack is still up**:

   ```bash
   docker compose -f docker-compose.dev.yml ps
   ```

2. **Re-confirm latest commits** to know where we resume:

   ```bash
   git log --oneline -10
   ```

3. **Tell me "go" and I'll start on Sprint B** — `com.longexposure.deepplus.OrderBook` class, `OrderBookManager`, JUnit tests with synthetic message streams. After it lands, the second half of Sprint D unblocks (BBO-level cross-validation vs the loaded TOPS QuoteUpdate stream).

---

## DEEP+ v1 sprint work

Full layout in @plan.md. Quick status:

### Sprint A — parser ✅ done 2026-05-11

- [x] `com.longexposure.deepplus` package
- [x] Sealed `DeepPlusMessage` interface
- [x] 7 trading-message records: AddOrder (a), OrderModify (M), OrderDelete (R), OrderExecuted (L), Trade (T), TradeBreak (B), ClearBook (C)
- [x] `DeepPlusMessageRouter`
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

### Sprint C — storage extensions

- [ ] New `orders` hypertable in `schema.sql` (or extend events — design decision after seeing data shape)
- [ ] `TimescaleWriter` extensions for the new message types
- [ ] End-to-end DEEP+ run with DB write enabled

### Sprint D — cross-validation against existing TOPS data

- [x] **Trade-level done 2026-05-11**: SUM(OrderExecuted.size + Trade.size) per symbol from DEEP+ == SUM(TradeReport.size) per symbol from TOPS. 9,134/9,134 symbols matched exactly, 0 share delta across 770M total shares.
- [ ] **BBO-level**: per-second BBO derived from DEEP+ book == TOPS Quote Update BBO. Blocked on Sprint B.

---

## After DEEP+ ships

The remaining post-parser work, full details in @plan.md:

- [ ] **Bootstrap baselines**: parse 30 days of DPLS HIST → continuous aggregate populates → ready for scorer
- [ ] **Event scoring** (`com.longexposure.scoring.EventScorer`) — the algorithmic core. Per-event-type scoring with transparent JSON breakdown for "why this event scored high"
- [ ] **LLM narration** — prompt engineering against `llama-large.joi`. Expected to be the hardest part of the project.
- [ ] **Temporal workflow** — convert the smoke-test loop into a proper workflow with retries + heartbeats
- [ ] **FastAPI endpoints** — the read-only API consumed by vedanta-systems
- [ ] **vedanta-systems integration** — `/api/long-exposure/*` nginx route + `long-exposure-browser.tsx` component
- [ ] **Production bring-up** on luv (Caddyfile entries from `deploy/INFRA-NOTES.md`)
- [ ] **Public launch** with 30-day backfilled narrative archive

---

## TOPS work — repurposed as validation oracle (not deferred, not deleted)

Everything we built today for TOPS stays in the repo. Its role changes:

- TOPS decoders (`com.longexposure.tops.*`) — produce the reference data for DEEP+ cross-validation
- The 285M quotes + 7.75M trades already in Postgres for 2026-05-08 — the cross-check reference
- `TopsMessageRouter` — keeps working; v1 production pipeline calls `DeepPlusMessageRouter` instead but TOPS code is exercised in validation runs

---

## Deferred / phase 2 (post-launch)

- [ ] Real-time streaming (requires the IEX SIP feed; different licensing model)
- [ ] Multi-exchange comparison
- [ ] User accounts / saved searches / alerts
- [ ] DEEP / TOPS-only historical analysis for pre-2025 dates (DPLS history doesn't go back that far)

---

## Bug-catching infra (low-but-real priority)

- [ ] **`DailyTotalsValidator`** — cross-check parsed trade volumes/counts against IEX's published per-symbol daily summaries. Works for both feeds. Still 0 LOC written. Needs the `/stats` endpoint shape figured out.
- [ ] **Reference-parser diff (TOPS only)** — wire `WojciechZankowski/iextrading4j-hist` as a test dep, diff message streams against ours for a sample TOPS file. Confirms TOPS decoder correctness (which we then use to validate DEEP+).

---

## Cleanup / done-today notes

- [x] ~~Frontend scaffold removed~~ (2026-05-10)
- [x] ~~Caddy `caddy.d/` per-project split~~ (2026-05-10, on the proxy side)
- [x] ~~Day 1–10 implementation~~ (2026-05-11)
- [x] ~~End-to-end 9.5 GB → 295M Postgres rows~~ (2026-05-11, 22:27 min)
