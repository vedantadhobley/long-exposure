# Pipeline Internals — full per-day flow

Single canonical reference for what happens inside the daily pipeline, end
to end. Complements `pipeline-architecture.md` (which is the function-name
vocabulary primer) and `temporal-design.md` (which is the workflow-level
layout). This doc is the "open the lid and look inside" view.

> **Naming**: pipeline-stage names are `DETECT / DESCRIBE / INTERPRET /
> SYNTHESIZE / AGGREGATE`. Earlier "Layer N" wording is deprecated.

## Per-day pipeline overview

```
DETECT (luv, no LLM)               DESCRIBE / INTERPRET / SYNTHESIZE (joi, LLM)
─────────────────────────          ──────────────────────────────────────────────
1. RefreshBaselines                7.  NarrateWorkflow         ─┐
2. MaterializeOrderLifecycle       8.  InterpretWorkflow         │  one-LLM-workflow-
3. ScoreEvents (9 scorers)         9.  SynthesizeDayWorkflow    ─┘  at-a-time rule
4. EnrichWithCoOccurrence
5. EnrichAnalytics                 then cascade (LLM, serial):
6. SelectTopEvents                 10. AggregateWeekWorkflow per touched week
                                   11. AggregateQuarterWorkflow per touched quarter (gated)
                                   12. AggregateYearWorkflow per touched year (gated)
```

The two halves are owned by different resource pools — luv (CPU + Postgres)
for DETECT, joi (single GPU, `LlamaClient.Semaphore(2, fair)`) for the LLM
stages. They share no in-flight resource, so v2 parallelization (Phase A
overlap with Phase B across days) is straightforward; v1 runs them
sequentially per day.

---

## DETECT — Scoring (luv, no LLM)

### 1. RefreshBaselinesActivity

```sql
CALL refresh_continuous_aggregate('daily_volume_by_symbol',
                                  ?trading_date, ?trading_date + 1 day)
```

Ensures the per-symbol daily volume row for the trading date exists in the
cagg before any inter-day scorer reads it. The cagg's hourly refresh policy
covers steady state; this explicit refresh closes a race where scoring
fires before the policy materializes today's data.

### 2. MaterializeOrderLifecycleActivity

Pairs each `orders_add` with its terminal event (`orders_delete` or last
`orders_executed`) once per day. Without this, every scorer that reads
order lifetimes would re-do a ~13 GB hash JOIN.

```sql
DELETE FROM order_lifecycle WHERE trading_date = ?;

WITH last_exec AS (
    SELECT DISTINCT ON (order_id, feed_source) ...
    FROM orders_executed WHERE trading_date = ?
)
INSERT INTO order_lifecycle
SELECT a.order_id, a.symbol, a.side,
       a.ts AS add_ts,
       COALESCE(d.ts, le.ts) AS terminal_ts,
       CASE WHEN d.ts IS NOT NULL THEN 'deleted'
            WHEN le.ts IS NOT NULL THEN 'executed'
            ELSE 'open' END AS terminal_state,
       EXTRACT(EPOCH FROM (COALESCE(d.ts, le.ts) - a.ts)) * 1e9 AS lifetime_ns,
       ...
FROM orders_add a
LEFT JOIN orders_delete d ON d.order_id = a.order_id ...
LEFT JOIN last_exec le ON le.order_id = a.order_id ...
WHERE a.trading_date = ?;

-- Durable per-symbol average lifetime baseline for time_in_book_drift
INSERT INTO daily_lifetime_by_symbol (day, symbol, avg_lifetime_ns, order_count)
SELECT ?, symbol, AVG(lifetime_ns), COUNT(*)
FROM order_lifecycle
WHERE trading_date = ? AND lifetime_ns IS NOT NULL
GROUP BY symbol
ON CONFLICT (day, symbol) DO UPDATE SET ...;
```

### 3. ScoreEventsActivity — the 9 scorers

Push-model: each scorer's `score(ctx, emit)` runs once and emits scored
events one at a time. The activity buffers ~10K rows of COPY text then
flushes to Postgres. Memory is bounded by per-cluster state inside the
scorer, regardless of total emission count.

**Intraday (7) — read raw wire tables:**

| Scorer | Reads | Detection | Breakdown leads |
|---|---|---|---|
| `halt` | `status_events` (kind='H') | LEAD() to find resume | `duration_humanized`, `halt_reason_label`, `halt_phase_span_label` |
| `large_trade` | `trades` | notional > $1M | `notional_dollars`, `pct_of_baseline_volume` |
| `sweep` | `orders_executed` | cluster ≥3 distinct prices in 10ms | `notional_dollars`, `distinct_levels`, `slippage_bps` |
| `post_cancel_cluster` | `order_lifecycle` | lifetime<50ms, gap<50ms, ≥20 orders | `orders`, `median_lifetime_ms`, `burstiness_class` |
| `layering` | `order_lifecycle` | post_cancel + ≥5 distinct prices | `orders × distinct_levels`, `price_range_bps`, `burstiness_class` |
| `iceberg` | `orders_executed` | ≥8 equal-size fills (CV≤0.20), ≥30s span | `fills`, `total_shares`, `display_ratio_pct`, `refill_cadence_class` |
| `liquidity_withdrawal` | `orders_delete` | ≥50 cancels in <100ms gaps | `deletes`, `rate_per_sec`, `withdrawal_side_class`, `pct_of_book_removed` |

**Inter-day (2) — read trailing baselines via `BaselineProvider`:**

| Scorer | Reads | Detection | Breakdown leads |
|---|---|---|---|
| `volume_deviation` | `daily_volume_by_symbol` cagg (400-day refresh) | today's volume vs trailing median, drift ≥ N× | `deviation_x`, `percentile_rank`, `robust_z`, `volume_regime_shift` |
| `time_in_book_drift` | `daily_lifetime_by_symbol` table | today's avg lifetime vs trailing median, ≥3× either direction | `drift_x`, `direction`, `percentile_rank` |

Score formula = base score × `BreakdownFmt.timeOfDayWeight(ts)` (Phase 7c,
attention-window boost for open/close events). Each scorer's `breakdown`
JSON is the contract the LLM later grounds against — every claim in prose
must trace to a field here.

`SymbolFields.apply()` joins per-ticker metadata (company name, exchange,
ETF flag, round lot, prev close) from the `symbols` reference table into
every breakdown.

### 4. EnrichWithCoOccurrenceActivity

The "nested signals" mechanism. A long sec-scale event (e.g.
`liquidity_withdrawal` lasting 11s) often *contains* many short ms-scale
events of other scorer types (post_cancel bursts, layering events) within
its `[ts, ts_end]` window — those nested events are the *mechanism* of the
larger one, not independent stories.

For each scored event above an interest floor, the activity:

1. Queries other-scorer scored_events on the same symbol whose intervals
   fall *inside* the parent's `[ts, ts_end]`
2. Aggregates summary stats per scorer type into a `co_occurring.during_event`
   block on the parent's breakdown
3. Sets `subsumed_by_event_id` on each child so `SelectTopEvents` skips them

The block layout (LLM-facing) is:

```json
{
  "co_occurring": {
    "during_event": {
      "post_cancel_cluster": {"count": 28, "sum_orders": 3759, "sum_total_shares": 568131},
      "layering":            {"count": 6,  "sum_orders": 187,  "sum_total_shares": 23400}
    }
  }
}
```

### 5. EnrichAnalyticsActivity

Post-select compute of windowed + book-replay stats too expensive for
in-scorer compute. Merges results into `selected_events.breakdown` via
idempotent `jsonb ||`.

**Windowed (cheap)** — read ±60 sec of trades/orders around each selected event:
- `slippage_bps`, `effective_spread_bps`, `pre_event_ofi`, `order_to_trade`
- `window_realized_vol_bps`, `window_jump_ratio`, `arrival_autocorr`
- `burstiness_fano`, `self_excitation`

**Book-replay (more expensive)** — single decode-only pcap pass with
`OrderBookManager`, snapshot book at each event ts:
- `depth_from_touch_near_bps`, `depth_from_touch_far_bps`
- `pre_halt_spread_bps`
- `pct_of_book_removed`, `book_recovery_pct`
- `book_depth_imbalance` + `book_depth_imbalance_class`

**Inter-day scorer enrichments** also land here:
- `time_in_book_drift`: `robust_z`, `percentile_rank` over trailing window
- `volume_deviation`: same + `volume_regime_shift` (CUSUM)

### 6. SelectTopEventsActivity

Within-scorer percentile rank for selection (no global cross-scorer
ranking — score units differ across scorers; within-scorer percentile
sidesteps this).

```
PER_SCORER_FLOOR = 3      (rare patterns guaranteed a meaningful set)
PER_SYMBOL_CAP   = 8      (rebalances TQQQ-dominated days)
```

→ `selected_events` populated with ~90-170 rows/day, each with a fresh
`BIGSERIAL selected_id`. Old rows for the date DELETEd first → idempotent.

---

## DESCRIBE — NarrateWorkflow (joi, LLM)

Per-event fan-out via sliding-window dispatch (max 2 in-flight). Each event
runs `NarrateEventActivity`:

```
1. Load selected_event from DB
2. event_hash = SHA256(scorer_id + breakdown
                       + BlueprintExtractor.PROMPT_VERSION
                       + ProseRenderer.PROMPT_VERSION)
3. Cache check: SELECT 1 FROM narratives WHERE event_hash = ? AND verifier_passed
   → if exists, UPDATE selected_id (FK relink) and return  ← skips LLM
4. NarrationPipeline.narrate():
   a. BlueprintExtractor.extract() ── LLM call #1 ──
      • SamplingParams.EXTRACT (temp 0.1, top_p 0.8, top_k 20)
      • Reads breakdown → emits blueprint JSON
        {what_happened, key_numbers:[{value, label, source_field}], ...}
      • Per-scorer narration-set guides which fields lead
        (halt → halt_phase_span_label; sweep → slippage_bps; etc.)
   b. Code copies breakdown.company_name → blueprint
      (deterministic; not LLM judgment)
   c. ProseRenderer.render() ── LLM call #2 ──
      • SamplingParams.RENDER (temp 0.7, top_p 0.8, top_k 20,
                               presence_penalty 1.5)
      • response_format = json_schema, strict=true
      • Emits {lead, facts[], co_occurring} 3-slot JSON
   d. Stitch slots into final prose
5. GroundingVerifier.verify(prose, blueprint, breakdown, scorerId) — 5 checks:
   • blueprint key_numbers ⊆ breakdown
   • prose numbers (digits + cardinal word forms) ⊆ blueprint ∪ breakdown
   • event symbol present in prose
   • prose company name agrees with breakdown.company_name
   • prose scorer-pattern mentions ⊆ {event's scorer} ∪ co_occurring keys
                                          (Phase 9b mislabel check)
6. If verifier fails: retry render up to 3× (sampling variance usually
   clears transient number-rendering glitches; extract is deterministic
   at temp 0.1 so it doesn't need to re-run)
7. UPSERT narratives row, keyed by event_hash
   (idempotent; ON CONFLICT updates prose + verifier status)
```

---

## INTERPRET — InterpretWorkflow (joi, LLM)

Per-event fan-out, same sliding window. Each event runs
`InterpretEventActivity`:

```
1. Load selected_event
2. If scorerId IN {'volume_deviation', 'time_in_book_drift'} (INTER_DAY_SCORERS):
   - Skip ±60-sec window query (day-level signal, not temporally anchored)
   - Use day-level prompt branch (Phase 9-A)
3. Else: TradeWindow.query() for pre (ts-60s, ts) and post (ts_end, ts_end+60s)
   - Pre-aggregated trade summaries: count, shares, notional, VWAP, range
4. Compute derived cross-window metrics:
   - post_event_reversion_pct
   - pre_to_post_vwap_move_bps
   (Attached under postJson.derived; rides into haystack + hash)
5. interpretation_hash = SHA256(scorer_id + breakdown + preJson + postJson
                                + InterpretEventActivityImpl.PROMPT_VERSION)
6. Cache check + FK relink (same pattern as Narrate)
7. LLM call ── SamplingParams.RENDER, with INTERPRET system prompt ──
   • Reads breakdown + catalog entry + pre/post windows + derived
   • For inter-day: only breakdown + catalog entry + drivers (no windows)
   • v12 prompt enforces framing rules:
     - HALT TIMING: use halt_phase_span_label verbatim; never render
       halt_start_et / halt_end_et nanosecond strings
     - display_ratio_pct → "displayed only N% of total size"
     - drift_x → "N× shorter/longer than typical"
     - pre_event_ofi ≈ 0 → "balanced" or omit
     - robust_z → "far above typical range", never "sigma"
   • Outputs 1-2 sentences, ≤350 chars, sequential/causal context
8. InterpretationVerifier.verify() — 6 checks:
   • Same 4 as Narrate + intent-claim denylist + scorer-pattern check
9. Retry × 3 on verifier fail
10. UPSERT interpretations row, keyed by interpretation_hash
```

---

## SYNTHESIZE — SynthesizeDayWorkflow (joi, LLM)

One LLM call per trading day. Runs `SynthesizeDayActivity`:

```
1. Load all per-event INTERPRET rows for the day (DISTINCT ON selected_id,
   latest verified; falls back to DESCRIBE for events with no INTERPRET,
   e.g. inter-day events in early datasets)
2. Compute day-level aggregates:
   • total_events, by_scorer, by_session_phase
   • top_symbols_by_event_count (top 12)
   • by_symbol_by_scorer + by_symbol_total truth maps
     (for AttributionVerifier — never exposed to the LLM, only to the
      verifier)
3. Build prompt: INTERP-only per event + day aggregates
   ~16K tokens; fits joi's n_ctx=32768 budget
   (INTERP-only because INTERPRET already restates DESCRIBE content; passing
    both would be redundant)
4. LLM call ── SamplingParams.SYNTHESIZE ──
   (Qwen instruct-reasoning preset: temp 1.0, top_p 1.0, top_k 40,
    presence_penalty 2.0; broader exploration for cross-event theme work)
5. SynthesisVerifier.verify():
   • Intent-claim denylist (manipul/spoof*/front-?run/wash-trad/illegal/fake)
   • Numbers (digits + cardinal word forms) grounded in haystack
   • Tickers must be in today's narrated set (no fabrication)
   • AttributionVerifier — extract (subject, count, scorer-type) triples
     from prose, check against the truth maps
     (e.g. "TQQQ recorded eight liquidity withdrawals" → fails if TQQQ
      had a different count, even though "8" appears elsewhere in haystack)
6. Retry × 3 on verifier fail
7. UPSERT daily_synthesis row, PK = trading_date
```

---

## AGGREGATE cascade — fires after all per-day done

For each `(week_start, quarter_start, year_start)` triple in
`computeCascadeScope(dates)`. Each tier is the same shape as the others —
reads its lower tier(s) plus a prior-window of same-tier siblings for
trend context.

### AggregateWeekWorkflow

```
1. Load this week's daily syntheses (Mon-Fri, latest per date)
2. Load prior 13 weekly rollups (one quarter of trend context)
   - Extends verifier haystack so "third straight week" claims can ground
   - Extends ticker-universe so multi-week mentions don't trigger
     fabrication checks
3. content_hash = SHA256(syntheses + prior weeklies + prompt_version)
4. If row exists with this hash → no-op (~1 sec) ← recompute-daily skip
5. Else: LLM call (SamplingParams.SYNTHESIZE, weekly aggregate prompt)
   - Prompt forbids cross-event number-summing
   - Prompt bars week-over-week phrasing when no prior weeks exist
   - Prompt bounds streak-length claims to prior_weeks + 1
6. SynthesisVerifier (+ AttributionVerifier with week-aggregated truth
   maps via PeriodAttributionMaps.load(week_start, week_end_exclusive))
7. Retry × 3
8. UPSERT weekly_aggregate, PK = week_start
```

### AggregateQuarterWorkflow (gated)

Mirror of weekly. Reads this quarter's ≤13 weekly rollups + prior 4
quarters. Gate: `weekly_count >= MIN_WEEKS_FOR_QUARTER (=8)` — returns 0
in <1 sec when not enough weeks have accumulated. Sit dormant until
first fire (~Sept 30 expected with current cadence).

### AggregateYearWorkflow (gated)

Mirror of quarterly. Reads this year's 4 quarters + prior 2 years.
Gate: `quarter_count >= MIN_QUARTERS_FOR_YEAR (=2)`. First fire ~Q3 2027.

---

## Concurrency rules (load-bearing)

| Resource | Limit | Enforced by |
|---|---|---|
| joi GPU throughput | 2 concurrent decode streams | `LlamaClient.Semaphore(2, fair)` (JVM-wide) |
| Narration task queue worker | 2 concurrent activities | `setMaxConcurrentActivityExecutionSize(2)` |
| Per-workflow fan-out | 2 in-flight Promises | sliding window via `Promise.anyOf` |
| LLM-bearing workflows across days | 1 at a time | operational rule, enforced by `PipelineWorkflow` running per-day sequentially |
| Postgres parse / score | 1 at a time | sequential `PipelineWorkflow` loop |

The three layers of LLM concurrency control (Semaphore + worker cap +
sliding window) are belt-and-suspenders. The single Semaphore would
suffice for correctness; the worker cap keeps Temporal scheduling clean;
the sliding window keeps the Temporal UI legible.

## Content-addressing summary

| Tier | Cache key | What invalidates it |
|---|---|---|
| Narrate | `event_hash = SHA256(scorer_id + breakdown + extract_PROMPT_VERSION + render_PROMPT_VERSION)` | Re-score (breakdown changes) or extract/render prompt bump |
| Interpret | `interpretation_hash = SHA256(scorer_id + breakdown + preJson + postJson + interpret_PROMPT_VERSION)` | Same + window data change (rare unless ±60-sec trades shift) |
| Synthesize | (none — always re-runs) | n/a |
| AggregateWeek/Quarter/Year | `content_hash = SHA256(children + prior-window siblings + prompt_version)` | Child period synthesis/rollup changes |

On a cache hit, the activity does ONE thing besides the skip: UPDATE the
existing row's `selected_id` to the current value (FK relink). This was
the structural fix for the orphaning bug — re-scoring produces new
selected_ids and the cached row used to point at a deleted row.

## Worker memory model

- Worker JVM: `-Xmx8g`
- Per-activity heap usage:
  - `MaterializeOrderLifecycle`: ~1 GB peak (JOIN buffer)
  - `ScoreEvents`: ~500 MB (per-cluster state across 9 scorers)
  - `EnrichAnalytics` (book-replay): ~1.5 GB (OrderBookManager state across all symbols)
  - LLM activities: ~100 MB (no heavy data structures)
- Postgres: `mem_limit: 48g`, `shared_buffers=8GB`, per-session
  `work_mem='2GB'` during the JOIN-heavy materialize step

## What changed tonight (2026-05-28 → 2026-05-29)

1. **Phase 7 — `halt_phase_span_label` data layer + DESCRIBE prompt**
   - `BreakdownFmt.haltPhaseSpan()` emits complete grammatical phase phrase
   - `HaltScorer` writes it as `breakdown.halt_phase_span_label`
   - `BlueprintExtractor.PROMPT_VERSION = extract-v10-halt-phase-span`

2. **Phase 7b — `BackgroundHeartbeat` in Narrate + Interpret**
   - Daemon thread fires `actx.heartbeat()` every 30 sec for full activity
     duration → LLM calls > heartbeat-timeout no longer die
   - Stage labels surface in heartbeat payloads (`keep_alive:llm:N`)

3. **Phase 8 + 8.1 — `PipelineWorkflow` + LLM_CHAIN mode**
   - Single Temporal-native entry point for cron + ad-hoc + backfill
   - `Mode.LLM_CHAIN` runs `Narrate → Interpret → Synthesize` per date
     without re-parsing
   - `computeCascadeScope(dates)` derives touched week/quarter/year anchors

4. **Phase 9-A — Inter-day INTERPRET branch**
   - `volume_deviation` + `time_in_book_drift` get full INTERPRET via
     day-level prompt (skips ±60-sec window query)

5. **Phase 9b — DESCRIBE pattern-mislabel verifier check**
   - `GroundingVerifier` 4-arg form enforces scorer-pattern grounding

6. **INTERPRET v12 — framing rules**
   - `InterpretEventActivityImpl.PROMPT_VERSION = interpret-v12-framing-rules-2026-05-29`
   - Halt timestamp leakage prevention
   - display_ratio_pct / drift_x / pre_event_ofi anchored renderings
