# TODO

Project scratchpad, agent + human editable. Cross-references @plan.md (sprint-by-sprint history), @decisions.md (architectural rationale), and @scoring-and-narration.md (the scoring + narration design spec).

Last refresh: 2026-05-18 (late).

---

## Where we are right now

**Single-day pipeline runs end-to-end on the 2026-05-08 trading day.** 364 M wire events parsed → triangle-validated (DPLS↔DEEP at 100.00 %, BBO at 99.42 %) → materialized into a derived `order_lifecycle` table → scored into 660 K candidate events → selected to 90 narratable events → narrated through the two-pass extract / render / verify pipeline against Qwen3.5-122B-A10B on `joi`.

**Today's principle: iron out every kink on a single trading day before backfilling 30.** No multi-day work until we trust the per-day output.

### Pipeline shape (the whole project in 6 things)

```
1. Parse           DEEP+ wire → 13 wire-format hypertables (one per message type)
2. Validate        DPLS book vs DEEP book vs TOPS BBO (3-way triangle)
3. Materialize     Pair add↔delete↔execute per order → order_lifecycle table
4. Score           7 intraday scorers → scored_events (≈ 660 K events / day)
5. Select          Per-scorer top-N → selected_events (90 events / day)
6. Narrate         Two-pass extract → render → verify → narratives
```

The seven scorers — halt, large_trade, sweep, post_cancel_cluster, layering, iceberg, liquidity_withdrawal — are the **entire detection vocabulary**. Plain-English descriptions live at the top of @scoring-and-narration.md.

---

## What's next, in order

### Today's session

1. **Confirm in-flight materialize+score completes cleanly** — passive wait, sanity-check that the lifecycle table delivers the promised sub-second PostCancel/Layering scans.
2. **Threshold-based selection** (~2 hr) — kill the hardcoded `PER_SCORER_CAPS`. Replace with within-scorer percentile rank + unified threshold + per-scorer floor/ceiling. Daily narration count becomes variable: quiet days produce ~30, dramatic days ~150. Pure SQL change to `SelectTopEventsActivity`.
3. **Cross-event linking, before selection** (~3-4 hr) — new `CombineRelatedEventsActivity` between Score and Select, groups overlapping `(symbol, ts→ts_end)` events into `scorer_id='combined'` rows with nested `constituents[]`. Linking signal is interval overlap (data-driven, not arbitrary time windows) with a tiny pair-table exception for non-overlapping but related events (e.g. halt → post-halt large_trade). See "Scoring + narration roadmap" section below for the full design.

### Next session (after today is solid)

4. **Score weight audit** — sort top-20 events per scorer per day, eyeball whether the multipliers (`log10(shares) × count`, etc.) actually surface "trader-flag-worthy" events. Adjust by feel; document why.
5. **Per-symbol liquidity-tier weighting** — multiply scores by a tier factor so a halt on AAPL ranks higher than a halt on a micro-cap. Requires symbol enrichment (already shipped) + a tier classification (mega-cap / large / mid / small / micro from `prev_close × shares_outstanding`).
6. **Layer 3 daily synthesis** — one LLM call per day that reads all narrations and produces a "today's themes" hero paragraph for the frontend. Full session of work.

### Explicitly NOT doing yet

- **30-day backfill** — deferred until single-day output is rock-solid. No point burning 30 hours of compute on a pipeline we're still tuning.
- **Inter-day scorers** (`VolumeDeviationScorer`, `TimeInBookDriftScorer`) — blocked on the 30-day backfill that we're not yet doing.
- **Layer-0 expansion** (the "recursive deeper dives" — per-event LLM pass that reads surrounding wire data) — full session of focused LLM-prompt work. Worth doing after threshold-selection + cross-event-linking land.
- **Parser-side lifecycle emission** (eliminating the JOIN entirely) — the architecturally cleanest fix, but the activity-level materialize already gives 95 % of the win. Not worth the parser refactor right now.
- **TimescaleDB columnar compression** on old chunks — only matters once we have multi-day history.

---

## Known pitfalls / risks (read this before adding more code)

Honest list of things that are weak, undertested, or might bite us. Order is by likelihood × impact, not by recency.

### Scoring layer

- **Score units are not comparable across scorers.** Halt scores are seconds (105–23 400). Large-trade scores are `log10($)` (6–8). Pattern scores are `log10(shares) × count` (mixed units). Threshold-based selection (item 2 in "today") fixes this for selection via within-scorer percentile rank, but the *raw* score column stays heterogeneous. Anyone joining `scored_events` to e.g. compute a global ranking will get nonsense.
- **Score weights were picked by feel.** Multipliers like `log10(shares) × cluster_count` were chosen at implementation time; no audit against "would a trader flag this in their top 20." Today's behavior is *defensible* but not *validated*. Item 4 in "next session" addresses this.
- **Halt scoring is linear in duration.** A 4 h halt scores 48× a 5 min halt, but isn't 48× more interesting (any halt > 30 min is "stock had a bad day, full stop"). Should be `log(duration)` or capped at 30 min. Trivial fix; queued.
- **No symbol-tier weighting.** Halt on AAPL ranks the same as halt on a micro-cap. Fix queued (item 5 in "next session"), depends on a tier classification we haven't computed yet.
- **No time-of-day weighting.** Events near open + close get more market attention; we treat them identically to midday events. Likely a small effect; not urgent.
- **Combined-event score is `max(constituents)` in the proposed design.** Defensible v1 but probably wrong long-term — a combined event should score *higher* than its strongest constituent if the constituents reinforce each other. Refine after seeing real flash-event days.

### Narration layer

- **Symbol fabrication.** One narration said "ODDTX" when the symbol was "ODTX" (extra D). Verifier currently checks numbers only, not named entities. Now that we have a `symbols` reference table, we can pass-list the symbol string to the verifier — easy fix, queued.
- **Verifier false negatives on rounded prices** — already canonicalized via `BigDecimal.stripTrailingZeros()` (so "$431.00" ↔ "$431"). But "approximately $431" with no exact match in breakdown is *not* caught; we rely on the prompt forbidding the LLM from inventing approximations.
- **Verifier doesn't catch tone-or-claim hallucinations.** "AAPL had a *catastrophic* halt" — the verifier checks numbers + (soon) symbols, not adjectives or causal claims. If the prompt says "factual, no editorializing" and the LLM editorializes, only human review catches it.
- **Single point of failure on `llama-large.joi`.** If the host is down, narration fails. The pipeline degrades gracefully (workflow continues, narratives just don't get written) but the user-facing site shows yesterday's narrations until joi recovers.
- **Throughput cap of 2 concurrent LLM calls** is fine at 50–100 narrations/day but bottlenecks any future fan-out (e.g. a Layer-0 expansion pass that calls the LLM per-event would need careful concurrency budgeting against the cap).

### Cross-event linking (proposed, not built)

- **Interval-overlap rule will sometimes link unrelated events.** Two `post_cancel_cluster` bursts on the same symbol at 09:31:00 and 09:31:08, intervals [09:31:00.100, 09:31:00.420] and [09:31:07.890, 09:31:08.150], won't overlap → won't merge. Good. But two clusters at 09:31:00.100 and 09:31:00.500, intervals [...0.100,...0.420] and [...0.500,...0.890] also don't overlap. Are they the same underlying event? Probably yes for a trader. We'll likely need a small "merge if gap < N ms" knob in addition to overlap.
- **`scored_events.subsumed_by_event_id` is a non-trivial schema change.** Backfilling existing scored_events rows with NULL (the new column) is safe, but anyone doing cross-day analytics needs to know about it.

### Operational

- **Materialize step is the new bottleneck.** ~20 min today on a 162 M-row INSERT. If it fails midway, pre-clean handles idempotency cleanly. But if mem_limit pressure ever causes the postgres container to get OOM-killed during the INSERT, the activity will retry — and if it keeps failing, the workflow stalls there with no scoring/narration. Worth a monitoring alert.
- **Postgres `mem_limit: 48g` is generous.** With legal-tender's ArangoDB at 24 GB + monitor stack + dev tools, host headroom is ~24 GB during scoring. If anything else runs hot at the same time we could OOM-kill something. The cron is at midnight ET specifically to avoid concurrency with other workloads, but ad-hoc runs need to be scheduled mindfully.
- **`order_lifecycle` table is now load-bearing.** If a schema change to `orders_add` / `orders_delete` / `orders_executed` lands without re-running `MaterializeWorkflow`, the lifecycle table goes stale. We have no test for this; need to remember.
- **Symbol metadata refresh runs weekly.** If a new IPO lists between refreshes, its narrations won't have company_name / sector. Acceptable for now; document as known limitation.

### Documentation

- The plain-English scorer overview lives in @scoring-and-narration.md (top of the doc). If we add scorers, this needs updating in lockstep with the registry.
- The "Pipeline shape (6 things)" mental model above is the single canonical view. If we add Layer-3 daily synthesis or Layer-0 expansion, this list grows. Don't let it grow to 12 things.

---

## Paste-ready commands

```bash
# Dev stack health
docker compose -f docker-compose.dev.yml ps

# Latest scoring layer state
docker exec long-exposure-dev-postgres psql -U leuser -d longexposure -c "
  SELECT scorer_id, COUNT(*) FROM scored_events
  WHERE trading_date='2026-05-08' GROUP BY scorer_id ORDER BY scorer_id;
  SELECT scorer_id, COUNT(*) FROM selected_events
  WHERE trading_date='2026-05-08' GROUP BY scorer_id ORDER BY scorer_id;
  SELECT COUNT(*) FROM narratives WHERE trading_date='2026-05-08';
"

# LLM endpoint health (used by narration)
curl -s http://llama-large.joi/v1/models | jq '.data[].id'

# Symbol enrichment cache state
docker exec long-exposure-dev-postgres psql -U leuser -d longexposure -c \
  "SELECT listing_exchange, COUNT(*), COUNT(*) FILTER (WHERE is_etf) AS etfs
   FROM symbols GROUP BY listing_exchange ORDER BY 2 DESC;"

# Trigger a fresh score+select (lifecycle materialize runs first)
docker exec long-exposure-dev-temporal temporal workflow start \
  --task-queue long-exposure-daily-pipeline \
  --workflow-id score-$(date +%Y%m%d-%H%M%S) \
  --type ScoreWorkflow \
  --input '"2026-05-08"'
```

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
- [x] **Event scoring** ✅ done 2026-05-16. Seven intraday scorers (halt, large_trade, sweep, post_cancel_cluster, layering, iceberg, liquidity_withdrawal). Push-model EventScorer interface; bounded memory; per-scorer top-N selection into selected_events. v8 produces 90 selected events per day. Full layout in @scoring-and-narration.md.
- [ ] **LLM narration** — the hardest design work in the project. Three activities per scored event: ExtractFacts → RenderProse → GroundingVerify. LlamaClient HTTP wrapper. Per-event hash caching in narratives table. Architecture in @scoring-and-narration.md.
- [ ] **Bootstrap baselines** (Sprint 3+) — run the workflow 30 times against historical HIST dates → continuous aggregate populates → enables interday scorers (VolumeDeviation, TimeInBookDrift). Not blocking for v1 launch.
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

## Scoring + narration roadmap (next major work, post symbol enrichment)

Captured 2026-05-18. These are the user-facing-quality improvements queued. Performance work in the next section is a prerequisite for some (especially the 30-day backfill).

### A. Cross-event linking — merge constituents into a richer parent event

- [ ] **Cross-event linking** — events on the same symbol within 1 s (configurable per scorer-pair, default 1 s, override e.g. `halt+large_trade` to 5 min) merge into a single `combined` row in `selected_events`. Breakdown shape:
  ```json
  { "kind": "combined",
    "primary_scorer": "liquidity_withdrawal",
    "constituent_scorers": ["liquidity_withdrawal", "post_cancel_cluster"],
    "time_span_ms": 300,
    "constituents": [ {"scorer_id":"…", "score":…, "breakdown":{…}}, … ],
    "company_name": "...", "listing_exchange": "...", ... }
  ```
  LLM gets one nested object and narrates one paragraph mentioning both aspects. Verifier needs to recurse into `constituents[].breakdown` — `GroundingVerifier.appendAllValues()` already walks arrays + objects so it Just Works; add a unit test. UI implication: the `long-exposure-browser` component needs tag chips like "✓ Liquidity withdrawal + ✓ Post-cancel cluster" above the paragraph.

### B. Threshold-based selection (replace hardcoded top-N caps)

- [ ] **Replace per-scorer top-N with percentile + threshold + floor.** Today's `PER_SCORER_CAPS` map (halt=20, large_trade=20, rest=10) produces exactly 90/day regardless of how dramatic the session was. Replace with:
    1. **Normalize within each scorer** — replace raw score with percentile rank within today's distribution for that scorer. Brings all scorers to a `[0,1]` scale.
    2. **Apply a unified threshold** — narrate everything > 99th percentile (tunable).
    3. **Per-scorer floor + ceiling** — guarantees catalog coverage on quiet days (floor ≥ 1) and bounds runaway dramatic days (ceiling ≤ 50).
  Net effect: quiet days produce ~30 narrations, dramatic days ~200, slow Tuesday ≠ Tesla-earnings day.

### C. Recursive deeper dives ("events within events")

- [ ] **Layer-0 expansion per Layer-2 event** — a second LLM call per scored event that reads the Layer-2 narration + a slice of surrounding Layer-0 data (price 5 min before/after, surrounding events on same symbol, day's volume context, related events on correlated symbols) and produces an "expansion" — "the layering came right before an 8,000-share market buy at the same price." See @concepts.md §5 + §10C. Cost: +1 LLM call per narrated event (~90/day → 90 extra LLM calls). Trade-off: doubles per-event budget for materially richer narration.

### D. Layer 3 — daily synthesis

- [ ] **One LLM call per day** that reads all 90 Layer-2 narratives + their Layer-0 expansions and produces a top-of-page paragraph: "today saw heavy halt activity at the open in small caps; IWM and TQQQ had coordinated liquidity events at 14:00 ET; large blocks concentrated in semiconductors." Drives the front-page hero text in the long-exposure-browser UI. Design contract: this LLM call reads the *structured outputs* of Layer 2 (prose + blueprint + expansion), never the raw firehose.

### E. Layer 4 — inter-day rollups

- [ ] **Weekly / monthly synthesis** — periodic LLM call producing "this week" / "this month" themes, reading multiple Layer-3 outputs. Needs ≥ 30 days of Layer-3 history before it's useful. Depends on (D) + 30-day backfill.

### F. Inter-day scorers (blocked on 30-day backfill)

- [ ] **`VolumeDeviationScorer`** — today's volume per symbol vs 30-day median. Output breakdown: `{ todays_vol, baseline_30d_median, deviation_x, baseline_window_days, baseline_window_status }`. Plug into the same Score → Select → Narrate pipeline as the intra-day scorers. Implementation depends on `BaselineProvider` interface backed by the `daily_volume_by_symbol` cagg.
- [ ] **`TimeInBookDriftScorer`** — KL-divergence-style distribution shift in order lifetimes per symbol vs baseline. "AAPL's median order lifetime collapsed from 800 ms to 90 ms" is a high-signal narration.
- [ ] **30-day backfill** — run the daily pipeline against the previous 30 trading days of DPLS HIST data. Sequential or parallel-by-date depending on host load. Lifecycle-table activity (below) is a prerequisite — 30 × current scoring runtime is ≈ 12+ hours; 30 × post-lifecycle runtime is ≈ 15 min.

### Stale / lower-priority scorer improvements

- [ ] Split halts by reason code (regulatory T1 vs LULD vs MCB) as separate sub-scorers — different stories, currently lumped.
- [ ] Spread-anomaly scorer — DEEP+'s book gives spread at every transaction; "spread widened to 5× rolling 5-min median, sustained ≥ 2 s" is a clean pattern that's not yet detected.
- [ ] Auction-event scorer — we parse auction-info messages but no scorer consumes them. IEX-listed names' opening/closing auctions are high-signal narratives other exchanges don't publish equivalents of.
- [ ] Score-weight tuning audit — multipliers were picked when writing each scorer; sort top-20 per scorer-day and confirm a trader would actually flag those. Haven't done that audit.
- [ ] Add `tags TEXT[]` column to `scored_events` for cross-cutting tags (`pre_market`, `ipo_day`, `options_expiry`). Lets selection filter/boost without new scorers.

---

## Performance — scoring layer

- [ ] **`order_lifecycle` materialized table** (the RIGHT fix to the PostCancel/Layering JOIN problem). Today PostCancel + Layering each run `orders_add ⨝ orders_delete ON (symbol, order_id)` over 162 M × 160 M rows on every scoring run. The hash table is ~13 GB, larger than any reasonable per-worker `work_mem`, so it spills to temp files (~9 GB observed). Each scorer runs this JOIN independently, so we pay the cost twice. Right shape: a new activity `MaterializeOrderLifecycleActivity` running once between Parse and Score that builds an `order_lifecycle` hypertable (one row per order: `order_id, symbol, side, add_ts, add_price, add_size, delete_ts, execute_ts, lifetime_ns, terminal_state`). PostCancel + Layering + future order-lifecycle scorers query that table as a sequential scan with an index on `(symbol, add_ts)`. Sub-second per scorer. Even better long-term: emit lifecycle rows directly from the parser since `OrderBook` already pairs Add+Delete at parse time — but that's a bigger refactor (touches writer + breaks the "one table per wire message" convention) and the activity-level materialization gets 95 % of the win for 10 % of the work. Defer the parser-side emission to a separate item.
- [ ] **Share the JOIN between PostCancel and Layering** (smaller win, only relevant if we don't do the lifecycle table). Both scorers run the SAME `orders_add ⨝ orders_delete` JOIN with `WHERE lifetime < 50 ms`; only the post-JOIN clustering rules differ (PostCancel: ≥20 orders, Layering: ≥5 distinct prices). Have PostCancel write its JOIN result to a session `TEMP TABLE` and Layering reads from it. Roughly halves total scoring time but is subsumed by the lifecycle table.
- [ ] **Per-symbol BRIN or partial indexes on `orders_add(symbol, ts)`** — for any scorer that ends up filtering by symbol within the trading day. Adding indexes to the wire-format tables is cheap insurance; the current `(symbol, ts DESC)` indexes on every hypertable are mostly sufficient but PostCancel's JOIN may not be using them as effectively as we think. Confirm with `EXPLAIN ANALYZE` after the lifecycle table lands.
- [ ] **TimescaleDB columnar compression on old chunks.** Once the 30-day backfill lands, the hot chunk (today's data) stays row-oriented for write speed, but every chunk older than 7 days gets compressed via `ALTER TABLE … SET (timescaledb.compress, timescaledb.compress_segmentby = 'symbol', timescaledb.compress_orderby = 'ts DESC')` + `add_compression_policy('orders_add', INTERVAL '7 days')`. Expect 10–20× disk savings and 3–5× faster sequential scans on the compressed chunks. Doesn't help today's PostCancel (hot chunk is uncompressed by design); transformative for any inter-day or 30-day-baseline scorer that scans a full month of history.
- [ ] **Drop `symbol` from PostCancel/Layering JOIN predicate.** Currently `JOIN ON (a.symbol = d.symbol AND a.order_id = d.order_id)` — defensive against a hypothetical parser bug that produces duplicate order_ids across symbols. IEX's DEEP+ spec guarantees order_id uniqueness per session, and we've never seen a violation in two trading days of cross-validation. Drop it to shrink the hash key from ~24 bytes to ~8 bytes → ~3 GB off the hash table at 162 M rows. Subsumed by the lifecycle table refactor; do it as a quick fix if we ship the activity-level rewrite first.

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
