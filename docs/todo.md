# TODO

Project scratchpad, agent + human editable. Cross-references @plan.md for full context and @decisions.md for the reasoning behind structural choices.

Last refresh: 2026-05-11 end-of-day. Pivot recorded: v1 is now DEEP+, not TOPS. See `docs/decisions.md` 2026-05-11 entry.

---

## ⏯ Start of next session — paste-ready checklist

1. **Download the 2026-05-08 DPLS file** (same date as the TOPS data already in DB, so cross-validation is symbol-for-symbol on the same day):

   ```bash
   curl -s 'https://iextrading.com/api/1.0/hist?date=20260508' \
     | jq '.[] | select(.feed == "DPLS")'
   # → copy the link, then:
   curl -L -o ~/workspace/data/long-exposure/raw/20260508_IEXTP1_DPLS1.0.pcap.gz '<link>'
   ```

   Expected size: ~7–9 GB compressed.

2. **Verify the dev stack is still up** (postgres + temporal + worker containers):

   ```bash
   docker compose -f docker-compose.dev.yml ps
   ```

3. **Tell me the file is ready** and I'll start on Sprint A (DEEP+ parser).

---

## DEEP+ v1 sprint work (top of priority)

Full layout in @plan.md "DEEP+ v1 sprints" section. Quick checklist:

### Sprint A — parser
- [ ] `com.longexposure.deepplus` package
- [ ] Sealed `DeepPlusMessage` interface (mirrors `TopsMessage`)
- [ ] 7 trading-message records: AddOrder (a, 0x61), OrderModify (M, 0x4D), OrderDelete (R, 0x52), OrderExecuted (L, 0x4C), Trade (T, 0x54), TradeBreak (B, 0x42), ClearBook (C, 0x43)
- [ ] `DeepPlusMessageRouter` (admin first, then DEEP+ trading)
- [ ] JUnit tests using spec worked examples — `deep-plus-1.02.pdf` at `~/workspace/data/long-exposure/specs/`, pages 16–22

### Sprint B — order book state machine
- [ ] `OrderBook` class: `Map<Long orderId, OrderState>` per symbol
- [ ] Updates on Add/Modify/Delete/Execute
- [ ] ClearBook drops all entries
- [ ] BBO derivation method
- [ ] Aggregate depth metrics

### Sprint C — storage extensions
- [ ] New `orders` hypertable in `schema.sql` (or extend events — design decision after seeing data shape)
- [ ] `TimescaleWriter` extensions for the new message types
- [ ] End-to-end DEEP+ run

### Sprint D — cross-validation against existing TOPS data
- [ ] Per-second BBO derived from DEEP+ book == TOPS Quote Update BBO (same symbol/second)
- [ ] Per-symbol DEEP+ Order Executed size sum == TOPS Trade Report size sum
- [ ] Investigate any divergence

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
