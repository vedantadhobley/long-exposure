# Cost Model + Pipeline Wiring

How the pipeline's stages fire, what data each reads and writes, how that data
flows to the daily / weekly / monthly scorers and narrative drivers, and what
all of it actually **costs** in LLM compute — especially when re-scoring or
backfilling.

Captured 2026-05-26 from a code audit of the narration/interpret/synthesize
path. Companion to [`temporal-design.md`](temporal-design.md) (per-workflow
mechanics), [`tiered-baselines-design.md`](tiered-baselines-design.md) (the
two-stack baseline/prose model), and [`pipeline-architecture.md`](pipeline-architecture.md)
(canonical stage vocabulary: WIRE / SUMMARIZE / DETECT / DESCRIBE / INTERPRET /
SYNTHESIZE / AGGREGATE).

---

## Part 1 — The LLM cost model

### 1.1 There is no LLM-skip cache (the load-bearing fact)

The README describes "caching by `event_hash`." **That is storage idempotency,
not compute-skip.** Confirmed in code:

- `ListSelectedEventsActivityImpl.list()` returns **every** `selected_id` for the
  date — no "already narrated" filter.
- `NarrateWorkflowImpl.run()` dispatches a `NarrateEventActivity` for **every**
  one of them.
- `NarrateEventActivityImpl.narrate()` **always** runs the two-pass pipeline
  (2 LLM calls) and then upserts. There is **no** `SELECT … WHERE event_hash = ?`
  guard before the LLM call.
- `InterpretEventActivityImpl` is the same shape: always calls `llama.chat`
  (its only skip is for scorers with **no pattern-catalog entry** — e.g.
  `volume_deviation` — which it logs and skips).

`event_hash` / `interpretation_hash` are used **only** as upsert keys:

```
event_hash = SHA256( scorer_id + breakdown_json
                     + BlueprintExtractor.PROMPT_VERSION
                     + ProseRenderer.PROMPT_VERSION )
ON CONFLICT (event_hash) DO UPDATE SET narrative, blueprint, verifier_*, render_*, model_id, created_at
```

So a re-run with the **same** breakdown + prompt versions **updates the existing
row in place** (no new row), but the LLM is **still called**. A prompt-version
bump or a changed breakdown produces a **new** hash → **new** row (the old one
is never pruned — see §1.4).

> Note: the upsert deliberately does **not** update `selected_id`. So when a
> re-score regenerates `selected_events` (new BIGSERIAL ids), the in-place-updated
> narratives keep their **original** `selected_id` → that FK goes stale. Consumers
> must join on the **content key** (`trading_date, symbol, event_type, event_ts`),
> not `selected_id`. The vedanta-systems `/day`, `/symbol`, `/event` routes do this.

### 1.2 Proof from the 05-22 re-score

Re-scoring + re-narrating 05-22 (to add `volume_deviation`) moved
`narratives` from **160 → 163**, while the worker logged **53+ LLM calls**:

- 160 intraday breakdowns were **byte-identical** across the re-score
  (`volume_deviation` anchors at 00:00, a whole-day event, so it never lands
  inside an intraday event's co-occurrence window → counts unchanged → same
  `event_hash` → in-place update, **no** new rows).
- Yet **all ~160 still hit the LLM** — because there is no skip.
- Only **3** rows were genuinely new (2 vol-dev + 1 changed sweep).

**With a hash-exists skip, that run would have been ~3 LLM calls instead of
~160.** That single data point is the whole scaling argument.

### 1.3 Per-run cost (today, no skip)

| Stage | LLM calls | wall-clock (2 concurrent on `llama-large.joi`) |
|---|---|---|
| DESCRIBE (`NarrateWorkflow`) | 2 × ~160 events | **~45–55 min/day** |
| INTERPRET (`InterpretWorkflow`) | 1 × ~160 (minus catalog-skipped) | **~30–40 min/day** |
| SYNTHESIZE (`SynthesizeDayWorkflow`) | 1 | ~25–95 s/day |
| AGGREGATE-weekly (`AggregateWeekWorkflow`) | 1 | ~20–25 s/week |
| AGGREGATE-monthly (future) | 1 | ~25 s/month |

**A full day of narration ≈ ~90 min of LLM**, serial (one-LLM-workflow rule).
This is paid **in full on every run** of that day — there is no incremental.

- **Re-scoring N historical days today** = N × ~90 min, serial → e.g. re-scoring
  05-18→05-22 (5 days) ≈ **~7.5 hours**, plus the affected weeks' AGGREGATE.
- **A nightly forward run** = ~90 min once. Fine.

### 1.4 Table growth

`narratives` / `interpretations` are upsert-by-hash and **never pruned**. They
grow with:
- **Prompt-version changes** — each bump creates a new hash → a new row per event
  (this is why 05-08, iterated v3→v7, has ~1,700 rows for ~160 events).
- **Re-scores that change a breakdown** — new hash → new row.

At launch scale these tables are still small (MBs), but it's why the read API
**must** dedup (latest verified per content-key). A periodic prune of
superseded rows is a post-launch nicety, not a launch blocker.

### 1.5 The fix that makes re-score/backfill cheap

Add a **hash-exists skip** before the LLM call in `NarrateEventActivity` and
`InterpretEventActivity` (or filter the fan-out list): *if a `verifier_passed`
row with this `event_hash` already exists, skip the LLM and keep it.*

- Safe because `event_hash` is provably stable for unchanged events (§1.2).
- Turns "add a scorer to N days of history" from N × ~90 min into **just the new
  events** (e.g. adding vol-dev to 5 days ≈ ~8 narrations → minutes).
- **Caveat:** only helps when breakdowns are stable. A *future intraday* scorer
  whose events land inside existing co-occurrence windows would shift those
  breakdowns → new hashes → those events correctly re-narrate.
- Small change; **do it before any large re-score/backfill.** Tracked in
  `todo.md`. Full design + the auto-invalidation model in **Part 6**.

---

## Part 2 — Wiring: which workflow fires when, reading/writing what

### 2.1 The two data stacks (one-directional)

```
  NUMERIC BASELINES (detection input, pure SQL)   →   events   →   PROSE THEMES (human output, LLM)
  daily_volume_by_symbol (+ monthly, future)            ↑              DESCRIBE → INTERPRET
        ↑ refreshed from trades                         │                  ↓
  scorers read trailing window ──────────────────────────              SYNTHESIZE (day)
                                                                            ↓
                                                                        AGGREGATE (week → month)
```
Numbers feed detectors → detected events get narrated → prose rolls up. Prose
never flows back into detection. Full rationale in `tiered-baselines-design.md`.

### 2.2 Workflow cadence + I/O

| Workflow | Fires | Reads | Writes |
|---|---|---|---|
| **DailyPipelineWorkflow** | cron 00:00 ET Tue–Sat (paused) + ad-hoc | — (orchestrates) | `pipeline_runs` |
| ↳ DownloadWorkflow | per day | IEX HIST | 3 `.pcap.gz` on disk |
| ↳ ParseWorkflow | per day | `.pcap.gz` | 13 wire hypertables (`orders_*`, `trades`, `quotes`, …); **`daily_volume_by_symbol` cagg auto-refreshes from `trades`** |
| ↳ ValidateWorkflow | per day (∥ Parse) | raw `.pcap.gz` | `validation_runs` |
| ↳ Materialize (in Score) | per day | `orders_add/delete/executed` | `order_lifecycle` |
| ↳ ScoreWorkflow | per day | wire tables + `order_lifecycle` + **`daily_volume_by_symbol`** (inter-day) + `symbols` | `scored_events` → (enrich) → `selected_events` |
| ↳ **NarrateWorkflow** (LLM) | per day | `selected_events` | `narratives` |
| ↳ **InterpretWorkflow** (LLM) | per day | `selected_events` + `trades` (±60 s window) | `interpretations` |
| ↳ **SynthesizeDayWorkflow** (LLM) | per day | `narratives` + `interpretations` (day) | `daily_synthesis` |
| ↳ CompressChunksActivity | per day | — | compresses day's chunks |
| ↳ CleanupWorkflow | per day | — | deletes `.pcap.gz`; **RetentionSweep** drops wire chunks + `order_lifecycle` + `scored_events`/`selected_events` older than **2 full weeks** (week-aligned) |
| **AggregateWeekWorkflow** (LLM) | weekly cron (future) + ad-hoc | `daily_synthesis` (the week) | `weekly_aggregate` |
| **AggregateMonthWorkflow** (LLM) | monthly (design) | `weekly_aggregate` (the month) | `monthly_aggregate` |
| **RefreshSymbolsWorkflow** | weekly cron Sun 02:00 ET (paused) | NASDAQ + SEC EDGAR + IEX SecurityDirectory | `symbols` |

LLM-bearing workflows (Narrate / Interpret / SynthesizeDay / AggregateWeek /
AggregateMonth) share `NARRATION_TASK_QUEUE` (worker cap 2) + the JVM-wide
`Semaphore(2)`. **Only one LLM-bearing workflow runs at a time** (operational
rule).

### 2.3 How baselines reach the scorers

- **Intraday scorers** (halt, large_trade, sweep, iceberg, layering,
  post_cancel_cluster, liquidity_withdrawal) read **only the current day's** wire
  tables / `order_lifecycle`. No cross-day input. Re-running a different day
  never affects them.
- **Inter-day scorers** (`volume_deviation` now; `TimeInBookDriftScorer` planned)
  read a **rolling trailing window** of `daily_volume_by_symbol` *as of the
  scoring date* — `today vs percentile_cont(0.5)` over the prior N days. The
  window is **continuous, week-agnostic**; it just reads whatever prior days are
  materialized in the cagg. Reach is bounded by how far back the cagg is kept
  (tiny → keep ~1 yr; see tiered-baselines-design). The cagg **outlives the raw
  wire data** (it decouples once materialized), so baselines survive the 2-week
  wire TTL.

### 2.4 How prose reaches the rollup drivers

Each prose tier reads **only the structured output of the tier directly below**
(never the raw firehose — keeps prompts small + grounded):

- **SYNTHESIZE (day)** reads that day's `narratives` + `interpretations` (uses
  INTERP per event, falls back to DESCRIBE when an event has no INTERP — e.g.
  vol-dev). → `daily_synthesis`.
- **AGGREGATE (week)** reads that week's `daily_synthesis` paragraphs. →
  `weekly_aggregate`. Verifier's allowed-ticker universe = union of tickers in
  the week's daily syntheses.
- **AGGREGATE (month, future)** reads that month's `weekly_aggregate`. →
  `monthly_aggregate`.

---

## Part 3 — The re-score cascade (bottom-up invalidation)

Re-running a **lower** stage invalidates everything **above** it for the affected
period. Re-scoring **one day** cascades:

```
ScoreWorkflow (day)          regenerates scored_events + selected_events (new selected_ids)
   ⇒ NarrateWorkflow (day)   re-narrates ALL ~160 events  (full LLM today)
   ⇒ InterpretWorkflow (day) re-interprets ALL ~160       (full LLM today)
   ⇒ SynthesizeDay (day)     re-synthesizes the day        (1 LLM)
   ⇒ AggregateWeek (week)    that day's WEEK is now stale  (1 LLM)
   ⇒ AggregateMonth (month)  that week's MONTH is now stale (future)
```

What does **not** cascade: other days' intraday scoring/narration (per-day,
independent); other weeks' aggregates; the numeric baselines of *earlier* days
(a trailing window only looks backward).

With the §1.5 skip in place, the Narrate/Interpret steps collapse to **just the
changed events**, making the cascade cheap.

---

## Part 4 — Scaling: steady-state vs backfill

| | Steady-state (nightly, forward) | Backfill / re-score |
|---|---|---|
| Order | day N scored after N-1, N-2… already loaded | may load history *behind* already-scored days |
| Inter-day baseline | complete the first & only time a day is scored | **stale** for days scored before their history existed → recalc |
| LLM cost | ~90 min once/night | N × ~90 min today (no skip); minutes with the skip |
| Recurring? | no recalc ever | one-time, only on out-of-order backfill |

**The recalc burden is a backfill artifact, not a design tax.** Production never
loads data behind a scored day, so baselines are always complete on first scoring
and nothing is re-run. The only reason 05-18→05-22 is stale for `volume_deviation`
is that we backfilled the *prior* week (05-11→05-15) after that week was already
scored (and before the scorer existed).

### Retention interplay
Raw wire (~13 GB/day) has the **2-full-week** TTL. Everything derived is tiny and
kept long/forever: the `daily_volume_by_symbol` cagg (baselines, ~1 yr),
`narratives` / `interpretations` / `daily_synthesis` / `weekly_aggregate` /
`monthly_aggregate` (the archive). So dropping the firehose costs only
**re-scoreability** of days older than 2 weeks, never the baselines or the
narrative archive.

---

## Part 5 — Data-update matrix (what changes per day / week / month)

| Object | Cadence | Updated by | Read by |
|---|---|---|---|
| 13 wire hypertables | per day | ParseWorkflow | scorers, validators, `daily_volume_by_symbol` cagg |
| `daily_volume_by_symbol` (cagg) | continuous (hourly policy) | auto from `trades` | inter-day scorers |
| `order_lifecycle` | per day | MaterializeOrderLifecycle | PostCancel / Layering scorers |
| `scored_events` | per day | ScoreEvents + EnrichWithCoOccurrence | SelectTopEvents |
| `selected_events` | per day | SelectTopEvents | Narrate, Interpret |
| `narratives` | per day (full re-run on re-score) | NarrateEvent (upsert by event_hash) | SynthesizeDay; API `/day` `/symbol` `/event` |
| `interpretations` | per day (full re-run on re-score) | InterpretEvent (upsert by interpretation_hash) | SynthesizeDay; API |
| `daily_synthesis` | per day | SynthesizeDay | AggregateWeek; API `/synthesis` |
| `weekly_aggregate` | per week | AggregateWeek | AggregateMonth (future); API `/aggregate` |
| `monthly_aggregate` | per month (future) | AggregateMonth | API |
| `symbols` | weekly | RefreshSymbols | scorers (enrichment) |
| `validation_runs` / `pipeline_runs` | per day | Validate / PipelineRunRecorder | ops, API `/health` |

**Kept forever:** narratives, interpretations, daily_synthesis, weekly_aggregate,
monthly_aggregate, symbols, validation_runs, pipeline_runs, the baseline caggs.
**2-week TTL:** the 13 wire hypertables, order_lifecycle, scored_events,
selected_events.

---

## Part 6 — Content-addressed skip / memoization (design)

Status: **design, not built** (2026-05-26). This is the backbone that makes the
pipeline cheap to re-run *and* automatically correct under change. Build the
minimal version (§6.5) before any large re-score/backfill.

### 6.1 What the skip does

Before an LLM stage computes an item, check whether a prior **verified** output
already exists for that item's content hash. If so, reuse it and skip the LLM
call; otherwise compute. Concretely for DESCRIBE:

```sql
-- in NarrateEventActivity, before pipeline.narrate(...):
SELECT 1 FROM narratives WHERE event_hash = ? AND verifier_passed = true
-- hit  → return the existing row, skip the 2 LLM calls
-- miss → run the pipeline, upsert (as today)
```

Skip only on `verifier_passed = true` so a previously-**failed** item is retried
on the next run (a prompt fix should get another shot); a passed item is reused.
(Tradeoff: a persistently-failing item re-runs every time — fine, failures are
~1%.)

### 6.2 The hash IS the invalidation oracle (why this is auto-adaptable)

The skip is only as smart as what the hash captures. The hash is a **fingerprint
of every input that affects the output**, so invalidation is automatic and
content-driven — you never maintain a "what's stale" list:

- **Scorer/methodology change** → different `breakdown` → new hash → recompute. ✓
- **Prompt change** → new hash → recompute. ✓
- **Nothing changed** → same hash → skip. ✓ (re-running old data is ~free)

This is **content-addressed memoization**. Re-run the whole pipeline over a year
of history after a tweak, and *only the items whose inputs actually changed*
recompute; everything else is a cache hit. "Did I invalidate the right things?"
stops being a manual worry and becomes a property of the data model.

### 6.3 Make it foolproof: hash the prompt TEXT, not a version string

Today the prompt enters the hash as a hand-maintained constant
(`PROMPT_VERSION = "v7…"`). Weak link: edit the prompt text but forget to bump
the constant → hash unchanged → **stale output silently reused**. Fix: hash the
**actual prompt template text** (plus model id + sampling params), so *any* edit
auto-invalidates with zero human discipline:

```
event_hash = SHA256( scorer_id
                   + breakdown_json
                   + describe_system_prompt_TEXT + describe_render_prompt_TEXT
                   + model_id
                   + sampling_params )          // EXTRACT + RENDER presets
```

Same idea for every stage. This is the version to build if we'll be iterating on
prompts and re-running old data (we will).

### 6.4 The pattern across tiers — and the automatic cascade

Every LLM stage is content-addressed: its output row stores a hash of *(its
ordered inputs + its prompt text + model + sampling)*; it skips if a verified row
with that hash exists.

| Stage | hash inputs | recomputes automatically when… |
|---|---|---|
| DESCRIBE (per event) | scorer_id + breakdown + describe prompts + model | the event's breakdown or the prompt changes |
| INTERPRET (per event) | breakdown + ±window summaries + interpret prompt + model | breakdown, window data, or prompt changes |
| SYNTHESIZE (per day) | sorted **hashes** of the day's narratives+interps + synth prompt + model | any narrative/interp in the day changed, or the prompt changed |
| AGGREGATE (per week) | sorted **hashes** of the week's daily syntheses + prior-week rollup hashes + agg prompt + model | any daily synthesis in the week changed (e.g. a new day landed), a prior-week rollup changed, or the prompt changed |

Because each tier's hash **includes the identities of the tier below**, a change
propagates up *exactly as far as it matters* and no further:

```
tweak one scorer's breakdown
  → that event's DESCRIBE hash changes      → re-narrate that 1 event
  → that day's SYNTHESIZE input-set changes → re-synthesize that 1 day
  → that week's AGGREGATE input-set changes → re-aggregate that 1 week
  → (nothing else recomputes)
change nothing → every stage is a cache hit, top to bottom
```

That's the bounded, automatic cascade: re-run anything, anytime; the system
recomputes precisely the affected slice.

### 6.5 "Rollups recompute daily" composes cleanly with this

The weekly rollup recomputes daily as "week-so-far" (see
`tiered-baselines-design.md` §4.3). With content-addressing it self-regulates:
Monday→Tuesday the week gains a day → the set of input daily-synthesis hashes
changes → the AGGREGATE hash changes → it re-runs (correct, new data). Re-run the
same day with nothing new → hash matches → skip. No gratuitous recompute, no
"is the rollup stale?" bookkeeping — the input-hash answers it.

### 6.6 Build scope

- **Minimal (do before any large re-score):** the DESCRIBE + INTERPRET
  exists-skip (§6.1), keyed on the *current* `event_hash` / `interpretation_hash`.
  This alone makes the 05-18→22 re-score and every future re-score cheap (only
  changed/new events run). ~half-day.
- **Foolproof (do when iterating on prompts):** switch the hash inputs from
  version strings to prompt **text** + model + sampling (§6.3).
- **Full (when SYNTHESIZE/AGGREGATE get the recompute-daily treatment):**
  content-address those two tiers on their input-hashes (§6.4) so the daily
  weekly-recompute and any synthesis re-run skip when unchanged.

All incremental — the per-event skip is independently shippable and is the piece
that unblocks cheap re-scoring.
