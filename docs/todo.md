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

## What's next, in order (revised 2026-05-20)

### Immediate (in order)

1. **Co-occurrence enrichment** (~2 hr) — `EnrichWithCoOccurrenceActivity` slots between Score and Select. For each scored event in a parent's interval, query co-occurring same-symbol other-scorer events and merge summary stats into the parent's `breakdown.co_occurring` block. Mark children with `subsumed_by_event_id`. Solves the "nested signals inside signals" problem deterministically without any time-window knob. See "Scoring + narration roadmap" section below for details.
2. **Delete the retired combining code** (~10 min) — `CombineRelatedEventsActivity[Impl]`, `CombineWorkflow[Impl]`, WorkerMain registrations. `subsumed_by_event_id` column stays (reused by enrichment).
3. **Layer 3 daily synthesis** (~half day) — `SynthesizeDayWorkflow` reads the day's narrations + day metadata, produces a "today's themes" paragraph. Handles same-symbol same-scorer repetition (the TQQQ-bursts pattern) dynamically — the LLM judges what's worth threading into the day-level story, no hardcoded gap thresholds.

### Next session (after the above settles)

4. **Score weight audit** — sort top-20 events per scorer per day, eyeball whether the multipliers (`log10(shares) × count`, etc.) actually surface "trader-flag-worthy" events. Adjust by feel; document why.
5. **Per-symbol liquidity-tier weighting** — multiply scores by a tier factor so a halt on AAPL ranks higher than a halt on a micro-cap. Requires symbol enrichment (already shipped) + a tier classification (mega-cap / large / mid / small / micro from `prev_close × shares_outstanding`).
6. **Layer 0 expansion** (per-event interpretation pass) — per-event interpretive second LLM pass that says what a pattern likely *means*, grounded in a pattern catalog + surrounding wire context. Different concern from Layer 3 — Layer 0 enriches individual narrations with interpretation; Layer 3 ties the day's narrations into a single thematic paragraph.

### Explicitly NOT doing yet

- **30-day backfill** — deferred until single-day output is rock-solid. No point burning 30 hours of compute on a pipeline we're still tuning.
- **Inter-day scorers** (`VolumeDeviationScorer`, `TimeInBookDriftScorer`) — blocked on the 30-day backfill that we're not yet doing.
- **Parser-side lifecycle emission** (eliminating the JOIN entirely) — the architecturally cleanest fix, but the activity-level materialize already gives 95 % of the win. Not worth the parser refactor right now.
- **TimescaleDB columnar compression** on old chunks — only matters once we have multi-day history.
- **Cross-event combining of any kind at the data layer** — explicitly retired 2026-05-20 after testing showed it conflates two different concerns (nested mechanism vs cross-day repetition). The two replacement layers (co-occurrence enrichment + Layer 3 synthesis) handle each concern at the appropriate abstraction.

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

- **Symbol fabrication (FIXED 2026-05-18).** One narration said "ODDTX" when the symbol was "ODTX" (extra D). Verifier originally checked numbers only, not named entities. Fix shipped: `GroundingVerifier` now takes a `Set<String> validSymbols` loaded once from the `symbols` reference table at narration-activity start; every all-caps 2-5-letter token in prose must equal the event symbol or be a real ticker in `symbols`. The prompt was also tightened ("the ONLY ticker that may appear is the event subject") and `Humanize.toEtTime()` no longer emits a trailing `" ET"` so the LLM doesn't see ticker-shaped abbreviations in the breakdown. No maintained denylist.
- **Verifier false negatives on rounded prices** — already canonicalized via `BigDecimal.stripTrailingZeros()` (so "$431.00" ↔ "$431"). But "approximately $431" with no exact match in breakdown is *not* caught; we rely on the prompt forbidding the LLM from inventing approximations.
- **Verifier doesn't catch tone-or-claim hallucinations.** "AAPL had a *catastrophic* halt" — the verifier checks numbers + (soon) symbols, not adjectives or causal claims. If the prompt says "factual, no editorializing" and the LLM editorializes, only human review catches it.
- **Single point of failure on `llama-large.joi`.** If the host is down, narration fails. The pipeline degrades gracefully (workflow continues, narratives just don't get written) but the user-facing site shows yesterday's narrations until joi recovers.
- **Throughput cap of 2 concurrent LLM calls** is fine at 50–100 narrations/day but bottlenecks any future fan-out (e.g. a Layer-0 expansion pass that calls the LLM per-event would need careful concurrency budgeting against the cap).

### Cross-event combining — built, tested, RETIRED 2026-05-20

- `CombineRelatedEventsActivity` was built, tested on 2026-05-08, and disabled because the interval-overlap rule absorbed ms-scale events into sec-scale events (28-36 constituents per combined event, mostly nested-mechanism cases like a liquidity_withdrawal absorbing post_cancel/layering events happening inside it). The combined narrations were unreadable and the verifier couldn't validate dotted source-field paths into nested breakdowns.
- The replacement architecture (co-occurrence enrichment + Layer 3 synthesis) is the new direction. See @decisions.md 2026-05-20 for the full rationale.
- The combine code stays in the repo until Phase B of the new plan deletes it. `scored_events.subsumed_by_event_id` column stays — it gets reused by enrichment.

### Operational

- **Materialize step is the new bottleneck.** ~20 min today on a 162 M-row INSERT. If it fails midway, pre-clean handles idempotency cleanly. But if mem_limit pressure ever causes the postgres container to get OOM-killed during the INSERT, the activity will retry — and if it keeps failing, the workflow stalls there with no scoring/narration. Worth a monitoring alert.
- **Postgres `mem_limit: 48g` is generous.** With legal-tender's ArangoDB at 24 GB + monitor stack + dev tools, host headroom is ~24 GB during scoring. If anything else runs hot at the same time we could OOM-kill something. The cron is at midnight ET specifically to avoid concurrency with other workloads, but ad-hoc runs need to be scheduled mindfully.
- **`order_lifecycle` table is now load-bearing.** If a schema change to `orders_add` / `orders_delete` / `orders_executed` lands without re-running `MaterializeWorkflow`, the lifecycle table goes stale. We have no test for this; need to remember.
- **Symbol metadata refresh runs weekly.** If a new IPO lists between refreshes, its narrations won't have company_name / sector. Acceptable for now; document as known limitation.

### Documentation

- The plain-English scorer overview lives in @scoring-and-narration.md (top of the doc). If we add scorers, this needs updating in lockstep with the registry.
- The "Pipeline shape (6 things)" mental model above is the single canonical view. If we add Layer-3 daily synthesis or Layer-0 expansion, this list grows. Don't let it grow to 12 things.

---

## Hardcode audit (things we'd want data-driven eventually)

Inventoried 2026-05-18 in response to a "no maintained denylists / arbitrary caps" pushback. Order is rough priority for migrating away.

### Tier 1 — Selection / filtering arbitrary caps

- [ ] **`PER_SCORER_CAPS` in `SelectTopEventsActivityImpl`** — `{halt: 20, large_trade: 20, others: 10}`. Picks a fixed 90 narrated events/day regardless of how dramatic the session was. **Fix**: threshold-based selection (within-scorer percentile rank → unified threshold + per-scorer floor + ceiling). Already on roadmap, queued next.
- [x] **No verifier denylist** — we considered a maintained `TICKER_DENYLIST` of non-ticker abbreviations (`ET`, `ETF`, `NMS`, ...) and explicitly rejected it. The verifier now uses the `symbols` reference table as the source of truth for "is this a real ticker." If the LLM emits something non-ticker-shaped (like "ET" for Eastern Time), the verifier flags it as a signal the prompt needs tightening — we don't paper over with a list. (Done 2026-05-18.)
- [ ] **Cross-event linking pair-windows** (proposed but not built). I floated a small table of `{(halt, large_trade): 5 min, (halt, liquidity_withdrawal): 30 sec}` for non-overlapping-but-related event pairs. **Don't build with this hardcode**. Start with interval-overlap only — if it misses obviously-related pairs, observe what's missed and decide whether the exception table is worth it. Don't add the table preemptively.

### Tier 2 — Scorer thresholds (magic numbers, finite but never audited)

Currently 12 thresholds across the 7 scorers:

| Scorer | Constant | Value | What it gates |
|---|---|---|---|
| LargeTrade | `NOTIONAL_CUTOFF_DOLLARS` | $1 M | Min trade size to score |
| PostCancel | `MAX_LIFETIME_NANOS` | 50 ms | Add→Delete pair counts as "short-lived" |
| PostCancel | `CLUSTER_GAP_NANOS` | 50 ms | Max gap between events in same cluster |
| PostCancel | `MIN_ORDERS_PER_CLUSTER` | 20 | Min orders to emit a scored event |
| PostCancel | `MAX_CLUSTER_SIZE` | 10 000 | Buffer cap before emit-and-reset |
| Layering | (mirrors PostCancel) | — | Same shape + `MIN_DISTINCT_LEVELS = 5` |
| Iceberg | `MIN_FILLS` | 8 | Min equal-size fills to score |
| Iceberg | `MIN_DURATION_NANOS` | 30 sec | Min span of fills |
| Iceberg | `MAX_SIZE_CV` | 0.20 | Max coefficient-of-variation across fill sizes |
| Iceberg | `RUN_GAP_NANOS` | 10 min | Max gap between fills in same run |
| Sweep | `CLUSTER_GAP_NANOS` | 10 ms | Max gap between fills in same sweep |
| LiquidityWithdrawal | `MIN_DELETES` | 50 | Min cancels to score |
| LiquidityWithdrawal | `CLUSTER_GAP_NANOS` | 100 ms | Max gap between cancels |

**Why they're slop**: they were picked by feel when each scorer was written. Never audited against "would a trader actually flag these as the day's top 20." Hardcoded in Java means tuning requires recompiling + redeploying.

**Fix path** (next-session-or-later):
1. **Short term**: migrate to a `scorer_config` table read at scoring-activity start. One row per scorer with the thresholds as columns. Tuning becomes `UPDATE scorer_config SET min_orders = 25 WHERE scorer_id = 'post_cancel_cluster'` + `temporal workflow start --type ScoreWorkflow ...` — no rebuild.
2. **Long term**: per-symbol baselines. `MIN_ORDERS = max(20, p95(per-symbol cluster size over 30 days))`. The `LargeTrade` $1 M is the worst offender here — $1 M in AAPL is routine, $1 M in a small-cap is enormous. Symbol-relative thresholds make all 12 magic numbers data-driven.

Blocker for path #2: 30-day backfill, which is itself blocked on the single-day pipeline being rock-solid (which is where we are right now).

### Tier 3 — Score formulas (algorithmic choice, harder to make data-driven)

Each scorer has a hardcoded score formula:

- LargeTrade: `log10($notional)`
- Sweep: `log10($notional) × distinct_price_levels`
- PostCancel: `log10(shares) × cluster_count`
- Layering: `log10(shares) × distinct_price_levels`
- Iceberg: `log10(shares) × fill_count`
- LiquidityWithdrawal: `log10(deletes) × deletes`
- Halt: `duration_in_seconds`

**Issues**:
- Halt is linear in duration but interestingness probably isn't (4 h halt isn't 48× a 5 min halt).
- Mixed units across scorers, defeats global ranking. Threshold-based selection (Tier 1) sidesteps this via within-scorer percentile.
- No principled justification for `×` vs `+` vs other combinations.

**Fix path**: audit one scorer at a time. Sort top-20 events per scorer-day, confirm a trader would flag those. If not, tune the formula and document why. Not urgent until inter-day baselines are live.

### Tier 4 — Infrastructure / operational constants

- `RETENTION_DAYS = 30` in `DailyPipelineWorkflowImpl` — fine, basically a deployment policy.
- `RAW_DIR = "/storage/raw"` — fine, infrastructure path.
- `PLACEHOLDER_DATE = LocalDate.of(1970, 1, 1)` — sentinel value, intentional.
- Postgres tuning (`mem_limit: 48g`, `work_mem='2GB'` per-session, `shared_buffers=8GB`, `hash_mem_multiplier=2`) — in `docker-compose.dev.yml` + `MaterializeOrderLifecycleActivityImpl`. **Move to env vars eventually** so dev/prod can differ without code edits, but not urgent.
- `LlamaClient` `Semaphore(2, fair)` — 2 is a real physical constraint of the `llama-large.joi` single-GPU box, not arbitrary. Stays.
- Various workflow timeouts (`Duration.ofMinutes(60)` for materialize, etc.) — keep as code constants but generous enough to absorb the variance we observe.

**The principle**: hardcodes are fine if they're physical constants or won't grow. Anything you'd EDIT regularly to tune the product belongs in a config table or env var.

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

## Scoring + narration roadmap (revised 2026-05-20)

Originally captured 2026-05-18 and majorly revised after testing the cross-event combining experiment. See @decisions.md 2026-05-20 for the full retrospective on what was tried, what failed, and why the new architecture is shaped the way it is.

### The architectural insight (read this first)

Market events happen at **multiple time scales simultaneously**:
- ms-scale events (post_cancel_cluster, layering, sweep) — typically the *mechanism* by which larger events manifest.
- sec-scale events (liquidity_withdrawal, iceberg) — composed of multiple ms-scale events nested inside their interval.
- minutes-scale events (halts, daily-level patterns).

A liquidity_withdrawal lasting 11 seconds CONTAINS 28 post_cancel bursts within it — those bursts ARE the withdrawal at finer resolution. They aren't separate stories.

The wrong abstraction is "combine overlapping events into one richer event" (we tried this — produced 28-36 constituent clusters). The right abstractions split the concern by scale:

| Concern | Layer that handles it |
|---|---|
| **Nested signals inside signals** (one phenomenon at multiple scales) | `EnrichWithCoOccurrenceActivity` — deterministic, scoring-time |
| **Same-symbol same-scorer repetition across the day** (TQQQ's 11 morning withdrawal bursts vs 14:00 isolated event) | Layer 3 daily synthesis (LLM with day context) |
| **Cross-symbol coherence** (multiple 3x ETFs sharing a pattern) | Layer 3 daily synthesis |
| **Identifying individual events** | Scorers (unchanged) |
| **Selecting which events deserve narration** | Percentile-rank within scorer (unchanged) |
| **Per-event description** | Per-event narration (unchanged) |
| **What does a pattern signify** ("looks like spoofing", "post-halt unwind") | Interpretation layer (queued separately — see C) |

### A. Co-occurrence enrichment (NEW — replaces the retired combining work)

- [ ] **`EnrichWithCoOccurrenceActivity`** — slots between `ScoreEventsActivity` and `SelectTopEventsActivity` in `ScoreWorkflowImpl`. For each scored event above an interest floor, queries other-scorer events on the same symbol whose intervals fall inside the parent's `[ts, ts_end]`. Aggregates summary stats per scorer type into a `co_occurring` block on the parent's breakdown. Sets `subsumed_by_event_id` on each child row so selection skips them.

  Resulting breakdown shape on a parent event:
  ```json
  {
    "symbol": "IWM",
    "deletes": 4895,
    "duration_s": 11.7,
    "rate_per_sec": 417.64,
    "company_name": "iShares Russell 2000 ETF",
    "listing_exchange": "NYSE Arca",
    "co_occurring": {
      "during_event": {
        "post_cancel_clusters": 28,
        "layering_events": 6,
        "median_concurrent_post_cancel_lifetime_us": 413,
        "total_concurrent_orders": 7843
      }
    }
  }
  ```

  Narration becomes deterministically interpretive:
  > "IWM experienced a liquidity withdrawal on IEX lasting 11.7 seconds, during which 4,895 orders were deleted. The withdrawal coincided with 28 rapid post-cancel bursts and 6 layering events on the same symbol during the same window, consistent with depth being pulled across multiple price levels simultaneously."

  **No knob for time-window.** Parent's own `[ts, ts_end]` defines the lookup window. **Cross-scorer only** — a sweep doesn't enrich with sweeps on the same symbol (that's the repetition problem, not the nesting problem). **Idempotent**: pre-clean by trading_date.

- [ ] **Delete the retired combine code** — `CombineRelatedEventsActivity[Impl]`, `CombineWorkflow[Impl]`, and their WorkerMain registrations. `subsumed_by_event_id` column stays (used by enrichment).

- [ ] **Verifier check** — confirm `GroundingVerifier.appendAllValues()` recurses into the `co_occurring` object so numbers like "28" and "413" are in the haystack. Should work without changes; add a test case.

### B. Threshold-based selection (replace hardcoded top-N caps)  ✅ DONE 2026-05-19

- [x] **Replaced `PER_SCORER_CAPS` map with percentile-rank rule** in `SelectTopEventsActivityImpl`:
  - Within each scorer, rank by score descending
  - Budget per scorer = `clamp(round(0.05 × event_count), 1, 30)`
  - Take top-`budget` events
- 2026-05-08 result: 164 events selected across 7 scorers (halt 6, iceberg 30, large_trade 8, layering 30, liquidity_withdrawal 30, post_cancel 30, sweep 30). Reasonable per-scorer balance.

### C. Layer 3 — daily synthesis (NEW priority — addresses the repetition problem)

- [ ] **`SynthesizeDayWorkflow`** — runs once at end of `DailyPipelineWorkflow`, after narration. One LLM call per day. Reads all the day's narrations + day metadata (date, session phases, day-of-week). Produces a single "today's themes" paragraph that handles:
  - Same-symbol same-scorer repetition narration ("TQQQ had 11 liquidity withdrawal events concentrated in the first 15 min of trading, with isolated events at 10:00, 11:30, and 14:00 ET — typical of 3x leveraged ETFs at the open")
  - Cross-symbol coherence ("similar morning bursts in TQQQ, SQQQ, and IWM")
  - Session-phase framing ("today's pattern was concentrated open volatility followed by a quiet midday")
- Slot at top of the daily page in vedanta-systems frontend.
- Design contract: reads the *structured outputs* of per-event narrations (prose + blueprint + breakdown), never the raw firehose. Same grounding discipline as per-event narration.

### D. Layer 0 expansion (queued — for when we want richer single-event interpretation)

- [ ] **Per-event interpretive second pass** — for selected events, an extra LLM call that reads the descriptive narration + the surrounding wire context (price 5 min before/after, symbol's typical activity) + the symbols-table enrichment, and produces an *interpretive* sentence explaining what the pattern likely means. See @concepts.md §5 + §10C. Cost: +1 LLM call per narrated event (~90/day). Likely paired with a "pattern catalog" of microstructure interpretations per scorer type.

### E. Layer 4 — inter-day rollups

- [ ] **Weekly / monthly synthesis** — periodic LLM call producing "this week" / "this month" themes, reading multiple Layer-3 outputs. Needs ≥ 30 days of Layer-3 history before it's useful. Depends on (C) + 30-day backfill.

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

## Parallelization audit (2026-05-20)

End-to-end walkthrough of pipeline stages — what's parallel, what could be, what isn't worth doing.

### Already parallel — no action

| Stage | What's parallel | How |
|---|---|---|
| `DownloadWorkflow` | 3 URL resolves + 3 file downloads | `Async.function()` × 6 in workflow code |
| `ValidateWorkflow` | 3 validator legs (DPLS↔DEEP, DPLS→TOPS, DEEP→TOPS) | `Async.function()` × 3, then `RecordValidationActivity` |
| `MaterializeOrderLifecycleActivity` | Within-Postgres parallel workers on the JOIN | `max_parallel_workers_per_gather=16` server-side |
| `SelectTopEventsActivity` | Postgres window functions | Single SQL, parallel inside PG |
| `NarrateEventActivity` | 2 events at a time | Dedicated task queue + worker cap + workflow-side sliding window |

### Queued — worth doing under specific triggers

- [ ] **Parallelize the 7 scorers in `ScoreEventsActivity`.** **Trigger: before 30-day backfill or when dev iteration cost gets painful.** Sequential scoring of 7 scorers takes ~13 min on 2026-05-08 data, dominated by 3 heavies (PostCancel ~3:44, Layering ~3:27, LiquidityWithdrawal ~3:50; everything else <10 s combined). Running them in parallel would bound total at the slowest individual scorer ≈ 4 min — **saves ~9 min per scoring run**. Implementation: split `ScoreEventsActivity` into N activities (likely one per scorer or per scorer-group), fire from `ScoreWorkflowImpl` via `Async.function()`. Each parallel activity needs its own JDBC connection + `work_mem` setting + COPY buffer. **Risk**: PostCancel + Layering both read `order_lifecycle`, running them simultaneously could re-introduce the spill-to-disk problem we fixed via the lifecycle table — need to verify under load. Effort: ~3 hours of careful work.

- [ ] **Day-level parallelism for the 30-day backfill.** **Trigger: only when actually running the backfill.** Each trading day's pipeline (parse → score → narrate) is independent. Sequential: 30 days × ~30 min = ~15 hours. Running 3-5 days concurrently could bring that down to ~5-6 hours. **Risks**: (1) Postgres mem pressure — running 5 days' scoring + materializing in parallel could OOM the host; need throttle (probably 3 max). (2) The narration task queue's worker cap is `2` global across all days, so concurrent days' narrations still serialize across the GPU cap. Concurrency is most effective on the parse + score + materialize stages, less on narration. Implementation: parent workflow that fires N `DailyPipelineWorkflow` child workflows in parallel with a configurable concurrency window (e.g., 3 at a time). Effort: 1-2 days of work.

### Considered — rejected for now

- **Parallelize `EnrichWithCoOccurrenceActivity` candidate processing.** ~350 candidates × ~10 ms each = 3.5 sec serial. Parallel would be ~ms. Not worth the engineering cost for sub-second savings.

- **Parallel symbol-metadata HTTP fetches** in `RefreshSymbolMetadataActivityImpl`. NASDAQ-listed + other-listed currently fetched sequentially; could be parallel. Saves ~500 ms per weekly refresh. Not worth ~30 min of work.

- **Parallel parse** — split pcap-ng stream across N worker threads. Currently ~35 min for 364 M rows. Hypothetical N-way parallelism could save 30-50 %. **Skip unless parse becomes a bottleneck**: production cron has plenty of headroom (35 min at midnight). High complexity, low marginal benefit, real risk of destabilizing a stable parser.

### Ranking

1. **#1 priority**: Parallel scorers — saves ~9 min/run, biggest single optimization.
2. **#2 priority**: Day-level parallelism — only matters once for backfill, but saves ~10 hours one-time.
3. Everything else: explicitly rejected.

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
