# Decisions Log

Append-only record of architectural and operational decisions, ordered by date. When a non-obvious choice gets made, append a dated entry here with: what we decided, what we considered, why we picked the chosen path, and what still leaves open.

> **Naming note (2026-05-22).** Older entries in this log use "Layer 0 / 1 / 2 / 3 / 4" pipeline-stage vocabulary that has been deprecated in favor of function names: Layer 1 = **DETECT**, Layer 2 = **DESCRIBE**, Layer 3 = **SYNTHESIZE**, Layer 4 = **AGGREGATE**, and the new per-event interpretation pass = **INTERPRET**. Historical entries are preserved verbatim — see [`pipeline-architecture.md`](pipeline-architecture.md) for the canonical current vocabulary.

---

## 2026-05-27 — Analytics wave (pre-launch): compute layer, zoom-ladder routing, EnrichAnalyticsActivity, narration-set, book-replay design

Full menu in [`analytics-catalog.md`](analytics-catalog.md); this entry records the *decisions* made while building the pre-launch slice. Goal: richer microstructure stats in the prose so each event type says something *quantified and non-obvious*, batched into ONE relaunch (adding a breakdown field changes the hash → forces re-narration, so a second relaunch is the cost of doing it piecemeal — batch it).

**1. Contract unchanged: code computes every number, the LLM only wraps, the pure-code verifier rejects anything ungrounded.** The analytics wave expands *what code computes*; it does not give the LLM tools or loosen grounding. New numeric fields only grow the verifier haystack, so they can never make a passing narration fail — confirmed empirically (05-22 stayed 403/403 after wiring 5 stats).

**2. Zoom-ladder routes which stat goes to which call** (the organizing principle, catalog §3.1):
- **DESCRIBE / breakdown = the event's own facts** — slippage, %-of-ADV, robust-z/percentile, burstiness, refill-cadence, two-sidedness. Computed at scorer-time (in-cluster) or post-select (DB lookup).
- **INTERPRET = the neighborhood** (sequence / cost / cause-shape) — reversion, OFI, surrounding-VWAP. These are *prompt-resident* (no breakdown slot), computed in `InterpretEventActivity` which already fetches ±60 s windows. **Not built yet** — they come with the INTERPRET prompt phase.
- **SYNTHESIZE / AGGREGATE = period-level** — concentration / breadth / entropy, not per-event detail.

**3. `EnrichAnalyticsActivity` (new) — post-select, lazy for the ~90–170 narratable events.** The home for breakdown stats that need DB access beyond a scorer's in-memory cluster (an `order_lifecycle` lookup, a window, or book replay). Runs after `SelectTopEvents` in `ScoreWorkflow`; merges fields into `selected_events.breakdown` via idempotent `jsonb ||`. Distinct from `EnrichWithCoOccurrenceActivity` (pre-select, absorbs nested children). Scorer-time stats (the event's own data: slippage, burstiness, cadence, pct-of-baseline, robust-z) stay in the scorers; only DB-dependent ones land here.

**4. `one_sidedness` dropped on the cluster scorers (degenerate); re-homed to `liquidity_withdrawal`.** `post_cancel`/`layering` cluster by `(symbol, side)`, so one-sidedness ≡ 1.0 (a clustering-key artifact). The genuinely informative side-imbalance is on `liquidity_withdrawal` (bid-side vs ask-side cancels). Implemented as **two-sidedness via `order_lifecycle.side`**, computed in `EnrichAnalyticsActivity` for the selected withdrawals — so detection stays unchanged on `orders_delete` (which carries no side) and there's no detection-count risk. Validated 05-22: 30/30 enriched, bid+ask = deletes, top ETF withdrawals correctly two-sided.

**5. Book-replay tier = a single decode-only pcap pass** (design locked, build deferred per #7). The book-state stats — sweep effective-spread, layering depth-from-touch, halt pre-event spread, liquidity_withdrawal %-of-book + recovery — need the BBO/depth at an event timestamp. Approach: stream the day's DPLS `.pcap.gz` once through the **validated** `OrderBookManager` (the same path proven 99.4% vs TOPS), snapshotting each selected event's symbol-book at its timestamp; ~2–3 min/day. The pcap is on disk during ScoreWorkflow (cleanup is last) and present for all 11 relaunch days (verified). Graceful skip if a pcap is ever absent. Lands in `EnrichAnalyticsActivity`.

**6. Narration-set = per-scorer headline guidance in the DESCRIBE extractor prompt** (`extract-v2-narration-set`). The extractor is a *universal* prompt that picks "the most salient facts" — left alone it ignored the new fields (chose `price_range_basis_points` over `slippage_bps`). Fix: a per-scorer "lead with these defining fields" table + framing rules, so the headline stat leads. Three-way **lead / weave-in / drill-down-only** split per scorer is documented in catalog §3.6. **Status: reasoned FIRST DRAFT, validated by the before/after single-day rerun** — the drill-down-only choices are the softest and may move once we read real prose. Two framing rules baked in: (a) `robust_z` is NEVER rendered as "σ"/"standard deviations" (values run 15–66 because volume is heavy-tailed → reads as hyperbole; lead with `percentile_rank` instead); (b) `withdrawal_side_class` is categorical ("two-sided" = de-risking framing, "bid/ask-side" = directional), no intent claims.

**7. Sequencing: narration-set + single-day rerun BEFORE the book-replay build** (option B over A). With 7 of 8 scorers already carrying a new stat (only `halt` needs book-replay), doing the narration-set + a single-day rerun *now* shows the analytics in prose today and **de-risks the ~1-day book-replay investment** — if a stat narrates poorly we learn before building more, and it delivers the single-day rerun the user wanted sooner. The book-replay build + the INTERPRET-tier window stats fold into the *final* relaunch afterward.

**What's wired (this session):** `Analytics` pure-fn layer (+tests); sweep `slippage_bps`, large_trade `pct_of_baseline_volume`, volume_deviation `robust_z`+`percentile_rank`, post_cancel/layering `burstiness_fano`, iceberg `refill_cadence_cv`, liquidity_withdrawal two-sidedness; `EnrichAnalyticsActivity`; narration-set prompt v2.

**Decisions still open:** (a) the narration-set split, pending the before/after read; (b) build the book-replay tier (effort + the halt stat); (c) the INTERPRET-tier window stats (reversion/OFI/VWAP) + their INTERPRET prompt; (d) **`implied_reserve` (iceberg) needs a defensible definition** before it's wired — it's an *estimate* of hidden size, and a confident fabricated number cuts against grounding; (e) the full 11-day relaunch (one batch, after the field set is final); (f) the analytics work trades against frontend time (the launch-critical path) — flagged, user chose analytics.

## 2026-05-27 — Prose rollups become a calendar fractal (day → week → quarter → year) + a generic cascade (direction; build post-launch)

**Supersedes** the 2026-05-26 "prose stack tops out at WEEKLY, monthly dropped" decision and clarifies that the 2-week retention (2026-05-25) bounds the **wire substrate only**, not the rollups.

**Decided (direction).** The prose stack extends into a calendar fractal: `daily SYNTHESIZE → weekly AGGREGATE → quarterly rollup → yearly rollup` (skipping monthly; quarter = 13 ISO weeks, year = 4 quarters). The yearly tier is the capstone retrospective ("a year in IEX microstructure" — the long-exposure idea at max exposure time, ~1 LLM call/year). Every tier is the same shape as today's `AggregateWeek`: read children fresh + a prior-window of same-tier siblings, content-addressed, **recompute when a child finalizes** ("period-so-far" while open, frozen when closed). Weekly prior-window widens 8 → 13 (one quarter).

**The cascade (the load-bearing new mechanism).** Each tier's `content_hash` already *detects* staleness (it includes children + prior-window). What's missing is the *trigger*: forward/nightly operation only touches the current period per tier, so a **historical backfill / re-synthesis / cross-history prompt bump** leaves downstream rollups stale — both vertically (day→week→quarter→year) and horizontally (a changed week ripples through the next 13 weeks' prior-windows). The fix is a `CascadeAggregate(fromDate)` driver that re-runs every period from `fromDate` forward, bottom-up by tier, and **relies on the content-hash to prune** — unchanged periods are no-ops, and the ripple self-terminates when a recompute yields identical prose. No fine-grained dependency graph; the driver is dumb, the hashes make it cheap + safe. Applies at every tier (quarter and year need it too).

**Retention clarification.** "Track a quarter/year" is *free* for the rollups (kept indefinitely, kilobytes) — it's just wider prior-windows + two more tiers. Only the **wire substrate** is heavy (a quarter ≈ ~850 GB), and it's needed only for re-scoring — so wire retention stays a separate, short knob; don't conflate it with the rollup horizon.

**Numeric side** cascades for free via TimescaleDB cagg range-refresh; the driver is a prose-stack concern only.

Full spec + build phasing in `tiered-baselines-design.md` §8. Not built — post-launch.

## 2026-05-27 — Phase 4: uniform dataset re-run — notional $-formatting, verifier-driven retry, co_occurring fixes

**Context.** Heading into launch the loaded 2-week dataset was a *mix* of states — different prompt versions across days, vol_deviation present on some days and not others, raw vs formatted numbers, several pre-retry synthesis failures. A uniform, all-passing rebuild was wanted, and an audit surfaced a few residual quality bugs worth fixing before a (one-time, expensive) full re-narration baked them in.

**Decided + shipped.**

- **Notional `$X,XXX.XX` formatting** — `BreakdownFmt.formatDollars(v)`, routed through the 6 notional fields (`notional_dollars`, `notional_per_fill`, `notional_per_level`). Counts/shares were already comma-formatted via `formatCount`; notional was a raw rounded double, so the model rendered "notional value of 1047129.4 dollars". Prices left numeric (their sub-penny precision shouldn't be flattened to 2 decimals).
- **Verifier-driven retry on all four LLM stages** (DESCRIBE / INTERPRET / SYNTHESIZE / AGGREGATE) — re-roll a verifier-rejected call up to 3× (the render/synth passes run at temp 0.7–1.0, so a fresh attempt produces different number-rendering that usually grounds), keep the first passing result, else the last (still stored `verifier_passed=false`). Drove every stage to ~100% pass (was 98–99%). DESCRIBE retries only the *render* pass — extract is temp 0.1, ~deterministic, and the failures are render-side.
- **co_occurring fixes** — integer `sum_deletes` (was `57.0`; added "deletes" to `isIntegerCountField`); dropped `total_children` (was narrated as "3 total children events", leaking the data-model vocabulary).
- **Uniform re-run** via `scripts/rerun-dataset.sh` (per day score→narrate→interpret→synthesize; per week aggregate). The per-event content-addressed skip + the retry make it incremental + self-healing — each day re-narrates only changed events and auto-recovers transient failures. Followed by the orphan prune (`docs/sql/prune-stale-narrations.sql`).

**Why retry rather than more prompt-engineering.** The residual failures are inherent to LLM rendering (the model occasionally derives or rounds a number slightly off a grounded value). Chasing each failure mode with a prompt rule is a treadmill; a re-roll against the *same unchanged* deterministic verifier is general, cheap (only failures pay extra calls), and doesn't weaken the grounding guarantee — the verifier stays the correctness mechanism, the retry just gives it more chances to land a clean draft.

## 2026-05-26 — Phase 3: durable inter-day baselines — 400-day cagg (decoupled from wire retention) + BaselineProvider + explicit refresh

**Context.** `VolumeDeviationScorer` (the first inter-day scorer) needs per-symbol trailing-volume baselines, but the wire data is dropped at 2 weeks. How do baselines survive when their source rows don't?

**Decided.**

- **Extend the `daily_volume_by_symbol` cagg refresh window 30d → 400d.** A continuous aggregate is its own materialized hypertable that *decouples from its source once materialized* — the hourly refresh persists each day's per-symbol volume row well before RetentionSweep drops the underlying `trades` chunks. So the cagg accumulates a rolling ~1 year of *exact* daily baselines (~2.2M tiny rows) even though raw wire data lives only 2 weeks. **Invariant (load-bearing):** the refresh window must never be shorter than wire retention, and RetentionSweep must never drop the cagg's materialization (pinned in both schema.sql and `RetentionSweepActivityImpl`).
- **`BaselineProvider` / `CaggBaselineProvider`** — moves all cagg SQL out of the scorer. Exposes *bulk* per-symbol maps (volume deviation is a set-scan "which symbols surged?", not per-symbol point lookups). Built once per scoring run, on `ScoringContext`. The reuse seam for future inter-day scorers (TimeInBookDrift) + the monthly numeric tier.
- **`RefreshBaselinesActivity`** as the first Score-phase step — an explicit `refresh_continuous_aggregate` for the trading day's bucket *before* the scorer reads it. Closes a real gap: a nightly run scored minutes after parse, before the hourly policy materialized today's trades, so the scorer would have read a cagg with no row for today. **No orchestration change** — it's a Score-phase activity, peer to materialize/score/enrich/select, so `DailyPipelineWorkflow` is untouched (it would only need surgery if this were miscategorized as a child *workflow* rather than an activity *step*).

## 2026-05-26 — Phase 2: AGGREGATE weekly rollup wired into the pipeline (recompute-daily, prior-week window, content-addressed)

**Context.** AGGREGATE (weekly themes) existed only as a manual, own-week-only activity. To be the top prose *trend* tier it had to be timely and grounded in week-over-week context.

**Decided.**

- **Recompute-daily "week-so-far"** — `AggregateWeek` runs every day on the open week (upsert by `week_start`; the last run of the week finalizes it). It reads *this week's daily syntheses fresh* (a **stateless rebuild** — deliberately NOT its own prior `aggregate_text`, which would compound LLM drift across the daily recompute) plus the **prior ~8 finalized weekly rollups** as week-over-week trend context (verifier haystack + ticker universe extended to them, so a grounded "third straight week of …" passes).
- **Content-addressed skip** — `weekly_aggregate.content_hash` over (prompt+model version + this week's day paragraphs + the prior weeks' paragraphs). The daily recompute does real LLM work only when a day is added/re-synthesized, a prior week changes, or the prompt bumps.
- **Wired into `DailyPipelineWorkflow`** after SYNTHESIZE (before compress); LLM-bound, shares the one-LLM-workflow-at-a-time rule.
- **Monthly *prose* rollup dropped (deferred)** — ~8 weekly paragraphs cover the near-term trend horizon; long-range *magnitude* reach lives in the (optional) numeric monthly cagg, a better home for quantitative long-horizon signal than prose. Full design in `tiered-baselines-design.md`.

---

## 2026-05-25 — Retention: rolling 2 full weeks, week-aligned (supersedes 30-day); backfill scoped to 2 weeks

**Context.** The original plan called for a 30-(trading-)day backfill + a flat 30-calendar-day `drop_chunks` retention. Two realizations changed this:

1. **30 trading days ≈ 6 weeks** (one calendar month ≈ 22 trading days). At ~13 GB/trading-day compressed that's ~390 GB of heavy wire substrate, plus ~3 days of mostly-serial LLM-bound compute to produce. That's a lot of disk and joi-time for a launch where the value is the *narrative archive*, not deep re-scoreability.
2. **The narrative archive and the wire substrate have completely different lifetimes.** `narratives` / `interpretations` / `daily_synthesis` are kilobytes/day and are the product — kept indefinitely. The wire hypertables + `order_lifecycle` + `scored_events` / `selected_events` are the heavy *re-score substrate*; dropping them only costs the ability to re-score days older than the window, never the visible archive (which grows one day at a time, forever).

**Decided.**

- **Retention = rolling 2 *full* weeks, week-aligned.** Keep the current (possibly partial) week plus 2 completed weeks; the 2-weeks-ago week drops only when the current week closes, so we never dip below 2 complete weeks. Boundary = `Monday(cutoff) − retainWeeks` (`RETENTION_WEEKS = 2`). Implemented in `RetentionSweepActivityImpl.weekBoundary()` (pure, unit-tested) shipped 2026-05-25.
- **Scope the backfill to 2 weeks, not 30 days.** We already had one week (05-18→05-22); backfilled one prior week (05-11→05-15) to reach the 2-week target. ~12 h serial, vs ~3 days for the 6-week version.
- **Closed two retention gaps in the same change:** `order_lifecycle` (a large derived hypertable that was never being cleaned) now chunk-drops; `scored_events`/`selected_events` DELETE by `trading_date`. `narratives`/`interpretations`/`daily_synthesis`/`symbols`/`pipeline_runs`/`validation_runs` are kept indefinitely.

**Why week-aligned and not a flat day count.** A flat "last 30 days" boundary is unaligned to the weekly AGGREGATE rollup and would mid-week leave you with a ragged 1.x-week trailing baseline. Week-aligned dropping guarantees the inter-day scorers and the weekly AGGREGATE always see whole weeks, and the policy reads naturally ("we keep 2 full weeks").

**What this enables / costs.** Enough trailing baseline for the inter-day scorers ("Nx the median") to be meaningful, plus a 2nd weekly rollup for a future monthly AGGREGATE — at ~130 GB floor / ~195 GB peak instead of ~390 GB. Cost: can't re-score a day once it ages past ~2 weeks (its wire rows are gone). Accepted; the policy `retainWeeks` knob extends to 4 weeks trivially if we later want deeper baselines.

## 2026-05-25 — Cross-node stage-pipelined backfill: considered, deferred

**Context.** The pipeline uses two disjoint resource pools — luv (CPU + Postgres: download / parse / score / materialize / compress) and joi (GPU: the three LLM stages, capped at 2 concurrent decode streams). In a strictly-serial per-day backfill, joi sits idle during luv's ~1.5 h of parse/score/compress, and luv sits idle during joi's ~50 min of LLM. The idea (raised 2026-05-25): pipeline across days — run day N+1's luv-side stages while day N's LLM stages run on joi — overlapping the two pools.

**Decided: keep serial; backlog the pipelined version.** Reasons:

- **The LLM time is an irreducible floor.** joi's 2-slot cap + the one-LLM-workflow-at-a-time rule mean the LLM stages of different days can never overlap each other. Total LLM time (~50 min/day) is the hard minimum for any backfill regardless of orchestration; pipelining only hides the luv-side time *behind* the LLM time. For a 5-day backfill that's roughly 12 h serial → ~5–6 h pipelined — a real ~2× but **one-time and small in absolute terms** now that we've scoped to 2 weeks.
- **It's only ever useful for backfill, never for the nightly cron** (one day/night has nothing to overlap) — correctly observed at the time.
- **Cost is real:** a parent workflow that staggers stages across days with an explicit cross-day LLM-phase mutex, plus error handling for a day failing mid-pipeline, plus testing — several hours of orchestration work and new failure modes, this close to launch.

The decision to cap retention at 2 weeks is what tips this: the optimization was compelling at 30 days (~3 days → ~1.5 days), marginal at 2 weeks. **Backlog it** — it becomes worth building if we ever extend retention to 4+ weeks or start doing frequent re-backfills. Recorded as the "day-level parallelism for backfill" item already in `docs/todo.md`'s parallelization audit; this entry captures *why we're not doing it now*.

## 2026-05-22 — Pipeline-stage naming pass: function names replace "Layer N"

**Context.** The original architecture used "Layer 0 / Layer 1 / Layer 2 / Layer 3 / Layer 4" pipeline-stage vocabulary inherited from `concepts.md`'s data-funnel pedagogy. Multiple problems compounded:

- "Layer 0" came to mean two different things in different docs — *raw wire events on disk* in `concepts.md`, but *the new per-event interpretation pass* in design docs written later. Conflating the two produced multiple conversations of pure confusion.
- The numbers carried no semantic content. A reader couldn't tell from "Layer 2" what the stage *did*.
- The numbering broke under insertion. When the interpretation stage was discovered to belong between description (Layer 2) and daily synthesis (Layer 3), naming it required fractional names ("Layer 2.5") or shoehorning it into the existing numbering ("Layer 0 expansion") — both obscured what the stage actually does.

**Decided.** Adopt function-name vocabulary for pipeline stages:

| Function name | Was | Means |
|---|---|---|
| WIRE | Layer 0 | Raw atomic events on disk |
| SUMMARIZE | (no name) | Background baselines + day-aggregates (planned) |
| DETECT | Layer 1 | The 7 scorers |
| DESCRIBE | Layer 2 | Per-event description prose |
| INTERPRET | "Layer 0 expansion" / "Layer 2.5" | Per-event surrounding-context interpretation |
| SYNTHESIZE | Layer 3 | Daily themes paragraph |
| AGGREGATE | Layer 4 | Weekly / monthly rollups |

Canonical reference: `docs/pipeline-architecture.md`. Older docs (`scoring-and-narration.md`, `concepts.md`, `decisions.md`, `todo.md`, `launch-sprint.md`, `pattern-catalog.md`) keep their "Layer N" wording in place — each has a top-of-file naming note pointing at the canonical reference. A bulk rename was attempted and reverted because false matches were too common ("Layer-4 verifier" in `concepts.md` referred to the 4-layer GroundingVerifier check tier, not pipeline stage 4). Per-file careful edits will land post-launch.

**Code renames in the same pass.**

- `Humanize` → `BreakdownFmt` (class now classifies session phases + duration buckets, not just humanizes durations; old name undersells the role).
- `Enrich` → `SymbolFields` (`Enrich.symbol()` → `SymbolFields.apply()` — old name was vague; new name names what gets added).
- `round2(v)` deleted — meaningless 4-character alias for `round(v, 2)`. Every caller now uses the explicit form.

## 2026-05-22 — INTERPRET architecture: Variant 2 (surrounding wire data) over Variant 1 (breakdown only)

**Context.** Two prototypes of the per-event INTERPRET stage were built. Variant 1 read only the event's own breakdown + the IEX catalog entry. Variant 2 additionally read a pre-aggregated ±60-sec window of trades on the same symbol around the event.

**Empirical comparison (21-sample smoke tests on 2026-05-08):**

| | Variant 1 (breakdown-only) | Variant 2 (surrounding-context) |
|---|---|---|
| Best example | "1.6 orders per level" | "block trade followed by another similar block 47 sec later" |
| Failure modes | ~15-20% arithmetic errors (LLM doing division at inference) | 0 arithmetic errors; ~20% mild causal speculation (prompt-tractable) |
| New code required | None beyond catalog | SQL window query + Java aggregation |
| Adds narrative the description doesn't already imply? | Marginally — most output is implicit in breakdown | **Yes — surrounding-context narrative cannot be derived from the breakdown alone.** |

**Decided.** Variant 2 ships. Reasons:

1. **Variant 1 is redundant with DETECT enrichment.** Every "wow" derived ratio Variant 1 produced (`orders_per_level`, `notional_per_level`, percentages, densities) is something DETECT can pre-compute and add to the breakdown — and now does, post the 2026-05-22 enrichment. With those fields available, DESCRIBE picks them up naturally and INTERPRET doesn't need to compute anything.
2. **Variant 2 adds information that's structurally not in the breakdown.** "The block was followed by another similar block 47 sec later" requires reading `trades` outside the event window. No amount of breakdown enrichment captures this.
3. **The arithmetic-error rate dropped to zero** because the task shifted from compute-arithmetic to read-and-compare-numbers, which LLMs are good at.

Variant 2 + Option A (LLM-driven) is the shipped architecture. Option B (templated substitution) was reconsidered for Variant 2 and rejected: a templated engine would have to substitute surrounding-window data, conditionally branch on whether pre/post windows have activity, and choose between "isolation" / "preceded by" / "followed by" / "flanked by" framings — that's halfway to a small DSL. Variant 2 + Option A handles all those conditionals naturally as prose.

Final verifier-pass rate on the full 164-event 2026-05-08 dataset: **98.78%** (162/164), after 5 prompt iterations chasing rounding, unit-conversion, ticker-presence, and approximation failure modes. Full prompt-version history preserved in `interpretation-design.md`.

## 2026-05-22 — SYNTHESIZE prompt uses INTERPRET-only per-event entries, not DESCRIBE+INTERPRET

**Context.** The SYNTHESIZE stage reads every per-event narration for the day and produces one cross-event themes paragraph. The first prototype concatenated both DESCRIBE prose ("AMD experienced a layering event involving 187 orders across 116 distinct levels…") and INTERPRET prose ("The AMD layering event spanned 166.0 ms across 116 distinct sell levels, immediately followed by a post-event window where 118 trades totaling 12,533 shares…") for each event. The resulting prompt was 38,208 tokens.

The llama.cpp server on `joi` is configured with `n_ctx=32768` (32K tokens, not the model's native 262K). First SYNTHESIZE run failed with `LLM 400: exceed_context_size_error`.

**Decided.** Drop DESCRIBE from the SYNTHESIZE prompt. Use INTERPRET only per event, falling back to DESCRIBE only when an event has no INTERPRET (~1-2% of events).

**Why this is architecturally right, not just a token-budget hack.**

INTERPRET prose already restates the descriptive content of an event ("The 5h 34m trading halt on MOBI (Mobia Medical, Inc.) began in pre-market trading…") and adds the cross-event-flavored sequential context that SYNTHESIZE actually needs ("with no preceding trades and minimal post-event flow"). DESCRIBE prose is strictly less informative for cross-event synthesis because it deliberately doesn't carry surrounding context.

In other words: SYNTHESIZE reading both DESCRIBE+INTERPRET would have been *redundant*, not *richer*. The token-budget pressure surfaced this redundancy. INTERPRET-only entries reduce the prompt to ~16K tokens — comfortable in the 32K context window — with no loss of relevant information.

**Why not raise `n_ctx` on the joi side instead.** Possible but operationally fragile (depends on user re-configuring joi; doesn't compose well with other models on the same host). The INTERP-only choice is correct on its own merits; the context-window hit was the forcing function that made us notice the redundancy.

## 2026-05-22 — `SamplingParams.SYNTHESIZE` preset (Qwen instruct-reasoning mode)

**Context.** DESCRIBE uses RENDER preset (temp=0.7, top_p=0.8, top_k=20, presence_penalty=1.5) — Qwen's "Instruct mode for general tasks" preset, verbatim. INTERPRET reuses RENDER. SYNTHESIZE is a different shape of task: it's reasoning across a corpus of ~164 narration paragraphs to find cross-event themes, not rendering a single known answer.

**Decided.** Add `SamplingParams.SYNTHESIZE` preset using Qwen's published "Instruct mode for reasoning tasks" parameters verbatim:

```
temperature=1.0, top_p=1.0, top_k=40, min_p=0.0, presence_penalty=2.0, repetition_penalty=1.0
```

Differences from RENDER:

- temperature 1.0 (vs 0.7) — broader exploration of phrasings
- top_p 1.0 (vs 0.8) — no nucleus cutoff
- top_k 40 (vs 20) — wider per-step candidate pool
- presence_penalty 2.0 (vs 1.5) — stronger anti-narrow-repetition (synthesis can collapse into "today saw heavy X, heavy X, heavy X" otherwise)

We do NOT use thinking mode (no `<think>` tags). The scorer does the reasoning; the LLM synthesizes. Thinking mode would ~10× cost for no measurable quality benefit on a "find themes across this corpus" task.

## 2026-05-22 — INTERPRET verifier accepts precision-rounded haystack values

**Context.** The first INTERPRET prototype's verifier used DESCRIBE's verifier verbatim: prose numbers must canonicalize to a value present in the breakdown. This failed on Variant 2 because the surrounding-window summaries carry unrounded VWAP / price values (`19.6883`) while the LLM naturally rounds to journalist precision (`$19.69`). Verifier flagged "19.69" as not found in haystack.

**Decided.** Extend `InterpretationVerifier` to accept precision-rounded equivalents:

- For each haystack number H ≥ 10, add d=0 rounded forms in HALF_UP / FLOOR / CEILING modes (covers both rounding-up and truncating LLM conventions for integer rendering).
- For each haystack number H regardless of magnitude, add d=1..4 rounded forms in HALF_UP and FLOOR (covers `72.7957` → `72.79` LLM-truncation and `72.7957` → `72.80` HALF_UP rounding).
- The bounded-magnitude guard for d=0 prevents the over-loose case where prose "5" would falsely match haystack `5.34`.

`SynthesisVerifier` uses the same precision-matching family. The looseness is intentional: it accepts journalist-register rounding while still flagging any number that doesn't trace to data within tolerable rounding.

---

## 2026-05-21 — SEC EDGAR as the canonical source for company names

**Context.** After landing structured render output (entry below) we observed a residual failure mode: the model would invent company names from training memory when the breakdown's `company_name` came from NASDAQ's `Security Name` field carrying SEC filing decoration ("Odyssey Therapeutics, Inc. - Common Stock"). The model would "improve" the noisy string and sometimes substitute a completely different company ("Oculus Dynamics Inc." for ODTX, "Mayweather Inc." for an AllianzIM ETF, "Anterix Inc." for Antelope Enterprise Holdings).

The root cause wasn't a prompt failure — it was a *data source* problem. NASDAQ's `nasdaqlisted.txt` and `otherlisted.txt` publish `Security Name`, which is designed to *identify a security* (and therefore needs filing class info to disambiguate common stock vs warrant vs preferred). It does not expose a clean "company name" field separately. The normalizer was extracting the company name from a security identifier — fighting the source.

**Decided.** Add SEC EDGAR's `company_tickers.json` as the primary source for `breakdown.company_name`, with NASDAQ Security Name as fallback for the long tail (mostly ETFs and warrants EDGAR doesn't list).

- `RefreshSymbolMetadataActivityImpl` fetches `https://www.sec.gov/files/company_tickers.json` (10,365 tickers as of 2026-05-21) after parsing the NASDAQ files
- EDGAR's `title` field is the SEC-registered entity name without filing decoration — "Apple Inc." rather than "Apple Inc. - Common Stock"
- Per-ticker preference rule: EDGAR wins when EDGAR is mixed-case; NASDAQ wins when EDGAR returns all-caps and NASDAQ has brand-correct mixed case (`NVDA` → NASDAQ's "NVIDIA Corporation" beats EDGAR's "NVIDIA CORP"; same for `FCEL` → "FuelCell Energy, Inc." beats "FUELCELL ENERGY INC")
- SEC's fair-access policy requires a User-Agent containing a contact email; we send `LongExposure/1.0 admin@vedanta.systems` and the policy is overridable via `SEC_USER_AGENT` env
- EDGAR fetch failure is non-fatal — falls back to NASDAQ + `CompanyNameNormalizer` cleanup

**Alternatives considered and rejected.**

| Alternative | Why rejected |
|---|---|
| Extend `FILING_DECORATION` to cover MLP/ETN suffixes via more tokens | Some words (`limited`, `partner`) collide with legitimate entity-type suffixes ("Antelope Enterprise Holdings Limited"). Token-walking can't distinguish suffix-as-decoration from suffix-as-identity. |
| Use OpenFIGI (Bloomberg's free reference data API) | Requires API key + per-request rate limits; SEC EDGAR is the official US issuer name source and is bulk-downloadable. |
| Maintain a static ticker → company-name lookup file | Works but requires manual updates; SEC EDGAR updates daily and is canonical. |
| Use the LLM itself to clean company names ("given this ticker, what's the clean company name?") | Re-introduces the fabrication risk the entire architecture is designed to prevent. |

**Tradeoffs accepted.**

- EDGAR's coverage is operating companies + most US-registered ETFs but not all ETNs / warrants / foreign listings. The 2026-05-21 refresh overlaid 4,668 of the 12,637 symbols. Fallback path (NASDAQ + normalizer with multi-word MLP/ETN-due-date pre-strip) handles the rest.
- EDGAR sometimes drops articles ("Trade Desk, Inc." for TTD where NASDAQ has "The Trade Desk, Inc."). Acceptable — both forms are journalistically valid.
- EDGAR is inconsistent in case: some titles mixed-case ("Apple Inc."), some all-caps ("MICROSOFT CORP", "NVIDIA CORP"). The preference rule handles this by keeping NASDAQ when its mixed case is the brand-correct form.

**Why the architecture is now stable.**

The normalizer (token-based, no maintained pattern list) is now a fallback that runs essentially as a no-op on EDGAR's clean output and only does meaningful work for NASDAQ-sourced names. The combined data flow is:

```
NASDAQ Security Name (noisy)  ┐
                              ├──> symbols.company_name
EDGAR title (clean, primary)  ┘             │
                                            ▼
                              Enrich.symbol() — normalizer (idempotent for clean)
                                            │
                                            ▼
                              breakdown.company_name (always clean)
                                            │
                                            ▼
                              BlueprintExtractor pass-through to blueprint
                                            │
                                            ▼
                              ProseRenderer uses verbatim in lead slot
                                            │
                                            ▼
                              GroundingVerifier Layer-4: agreement check
```

Every layer has one job. No code-side "substitution" rewriting the LLM's output.

---

## 2026-05-21 — Structured JSON output for the prose-render pass

**Context.** Prose iterations v3–v5 repeatedly leaked qualitative filler when the breakdown was thin. The v4 prompt brought filler down to ~0.6% via explicit forbidden-categories rules, but the residual cases all shared a shape: the model had unconstrained "freestyle space" to fill when there weren't enough facts to support a 2-3 sentence paragraph. Examples like "the security is now trading normally" and "marking the end of one of the longer trading interruptions recorded that day" — factually unsupported claims invented to satisfy the implicit length expectation.

Reactive prompt rules to forbid each filler pattern would not generalize; new patterns would appear with every run. The root issue was that prose has unbounded surface area.

**Decided.** Replace free-form prose output with a three-slot JSON object enforced at the sampler via OpenAI-compatible `response_format: json_schema, strict: true`.

```json
{
  "lead":         "<one sentence — subject + action + ≥1 key_number>",
  "facts":        ["<sentence>", "<sentence>", ...],
  "co_occurring": "<one sentence>" | null
}
```

`RenderResult` is the corresponding Java record; `stitched()` joins the slots into the final prose with single-space separators. The `narratives` table gets a `render_structured JSONB` column alongside the existing `narrative TEXT`, so downstream consumers can read prose or query semantically.

**Why this works structurally.**

- The model has nowhere to write filler. Every output token lives in a named slot whose semantics are constrained by the prompt and validated by the schema.
- `co_occurring` is `required` in the schema and nullable in type, with the user prompt explicitly indicating presence/absence based on the breakdown. The slot can't silently disappear (must be set to `null` or a sentence).
- Schema enforcement is at the sampler, not post-hoc validation. llama.cpp zeros the logits of tokens that would break the JSON structure, so what comes back is guaranteed parseable.

**Empirical validation (2026-05-08 dataset, 164 events).**

| Run | Verifier pass | Filler patterns |
|---|---|---|
| pre-Qwen RENDER (free prose, old prompt) | 164/164 | 1/164 (0.6%) |
| v3 Qwen RENDER (free prose, old prompt) | 41/41 (partial) | 4/41 (~10%) |
| v4 Qwen RENDER (free prose, adaptive length + forbid-list) | 164/164 | 1/164 (0.6%) |
| v6 Qwen RENDER (structured output, schema-enforced) | 164/164 | 0/164 (0%) |

Qualitative filler patterns ("now trading normally", "regular market activity") absent from v6 by construction; they had no slot to live in.

**Alternatives considered and rejected.**

| Alternative | Why rejected |
|---|---|
| Tighter prompt with explicit forbidden phrases | Reactive and unbounded — every new filler pattern would add a rule. The same anti-pattern as a maintained denylist, just in prompt-text form. |
| GBNF grammar (llama.cpp-native grammar constraint) | More powerful than JSON Schema for non-JSON outputs, but JSON Schema is more portable (any OpenAI-compatible server supports it) and easier to maintain. Reserved for future non-JSON structured-output needs. |
| Per-scorer-type prompts | Would diverge fast and require maintenance per scorer. Single prompt + schema handles all 7 scorer types. |
| Cap output via `max_tokens` | Mechanical but blunt — would truncate legitimately rich narrations. |

**Tradeoffs accepted.**

- Sentence ordering within the lead is fixed (subject + action + primary number). Free prose allowed reorderings that read more naturally for some events; structured output is more uniform.
- The model's prose-flow creativity is limited to intra-sentence word choice, not paragraph structure.
- Schema-enforcement is llama.cpp-specific syntax under the hood, though the OpenAI-compatible `response_format` shape is portable.

---

## 2026-05-21 — Don't pass company name through code substitution; pass through data instead

**Context.** A failed iteration during today's work, preserved here as a worked counterexample. After observing the v6 structured output had moved fabrication from "occasional in free prose" to "frequent in the lead slot" (model invented "Oculus Dynamics Inc." for ODTX, "Mayweather Inc." for an AllianzIM ETF, etc.), the first proposed fix was a `SubjectSubstitution` post-render transform: strip `company_name` from what the model sees, let it write the ticker, then code substitutes `"<company> (TICKER)"` into the prose after the verifier runs.

That approach was implemented (small helper class + tests + pipeline step) and then deliberately reverted before deployment, after user pushback identified it as overcomplicated. The issue: it makes the published prose diverge from what the model produced, requires handling parenthetical-vs-bare-ticker edge cases, and adds a presentation transform downstream of the verifier (so the verifier validates one thing and we publish another).

**Decided.** Use a data-pass-through architecture instead. The breakdown's `company_name` (cleaned via `CompanyNameNormalizer`, sourced primarily from EDGAR per the entry above) gets carried into the blueprint by `BlueprintExtractor.extract()` as a deterministic code-level copy after the LLM call returns. The renderer sees the canonical name in its input and uses it verbatim. The verifier's Layer-4 check enforces agreement between the prose's parenthetical-company form and `breakdown.company_name`.

**Architectural principle established.** The LLM is for prose flow, not for table lookups. When data is unambiguously known (ticker → company name), the right move is to plumb the data through cleanly, not to ask the LLM to retrieve it from memory. Code transformations should improve *what the model sees* (clean inputs), not *what the user sees* (post-render rewrites).

**The reverted attempt is preserved in git history** (commits 1410e09, 420d63c, 8cc3256, f7e536f and their reverts 45d5cb8, ce42d0d, dd6dd85). Future readers asking "why didn't we substitute company names in code?" can read the reverted diff and this entry to understand the bound.

---

## 2026-05-20 — Cross-event combining is the wrong abstraction; replace with co-occurrence enrichment + Layer 3 synthesis

**Context.** Built `CombineRelatedEventsActivity` to handle the IWM-style use case: two events on the same symbol firing 300 ms apart should narrate as one paragraph instead of two repetitive ones. The implementation merged any two events on the same symbol whose `[ts, ts_end]` intervals overlapped, packaging them under a synthetic `scorer_id = 'combined'` row with a nested `constituents[]` breakdown.

**What we observed on 2026-05-08 real data.** The combine algorithm produced **181 888 combined events absorbing 506 630 constituents** out of 660 949 raw scored events. The clusters were dominated by 28–36-constituent groupings, almost all of the form "one long sec-scale event + dozens of ms-scale events happening inside its interval":

- A liquidity_withdrawal lasting 11.7 s on IWM absorbed 28 post_cancel_clusters and 6 layering events occurring during those 11.7 s.
- Layering events were 100 % subsumed across the day (every layering event happened inside some larger interval).
- The combined narrations were unreadable: "involving 28 constituents" leaked metadata into prose, and the verifier couldn't validate dotted source-field paths into nested breakdowns.

We also looked at TQQQ: 14 liquidity_withdrawal events selected for the day, 11 of them concentrated in the first 15 minutes after market open (consecutive gaps 5 s–5 min), three isolated events at 10:00, 11:30, and 14:00 ET. Naively merging "same symbol same scorer" across the day would have collapsed all 14 into one narrative, destroying the distinction between the open burst and the midday isolated events — a market-structure error, not a taste call.

**Root cause analysis.** The combine activity was trying to solve TWO different problems with one rule:

1. **Nested signals inside signals (mechanism nesting)** — a long sec-scale event whose mechanism IS the dozens of ms-scale events happening inside it. The IWM liquidity withdrawal at 14:00:31 IS the rapid post-cancel bursts and layering activity that occurred during it. These aren't independent stories; they're one phenomenon at different zoom levels.

2. **Same-symbol same-scorer repetition across the day** — TQQQ's 11 morning liquidity_withdrawal bursts vs the isolated 10:00 / 11:30 / 14:00 events. Whether to group these is a *narrative* judgment that depends on market session phase, news context, and other meta-information the data layer doesn't have.

A single overlap-based rule treats (1) and (2) identically — and gets both wrong. (1) gets over-absorbed (every nested ms-event becomes a constituent). (2) gets under-handled (events spanning open + midday + close don't overlap so don't combine, even when a trader would consider them one symbol's bad day).

We also explored "make the combine rule smarter" — adaptive gap detection, elbow detection on the gap distribution, time-of-day phases. Each adaptive rule either still required a knob (split-ratio threshold; sparse-data fallback) or violated market microstructure semantics. There is no knob-free deterministic rule for "same burst" that's correct across the data we observed.

**Decided.** Split the two concerns into separate layers:

| Concern | Where it's solved |
|---|---|
| Nested signals inside signals | New `EnrichWithCoOccurrenceActivity` at scoring time (deterministic) |
| Same-symbol repetition across the day | Future Layer 3 daily synthesis (LLM-based, runs after narration) |
| Identifying individual events | `ScoreEventsActivity` (unchanged) |
| Selecting which events deserve narration | `SelectTopEventsActivity` (unchanged — percentile rank within scorer) |
| Per-event description | `NarrateEventActivity` (unchanged — one paragraph per selected event) |

`CombineRelatedEventsActivity` + `CombineRelatedEventsActivityImpl` + `CombineWorkflow` + `CombineWorkflowImpl` get deleted. The `subsumed_by_event_id` column on `scored_events` stays — it's reused by enrichment to mark child events that have been absorbed into a parent's `co_occurring` block.

**Why this is the right shape.**

1. *One abstraction per phenomenon.* Nesting is structural (always present in market microstructure); deterministic enrichment is the right tool. Cross-day storytelling is judgment-shaped; an LLM with day context is the right tool. Forcing both through one mechanism was the original mistake.

2. *Enrichment has no knob.* The parent event's own `[ts, ts_end]` defines the lookup window. No `MAX_GAP_MINUTES` or `MIN_CLUSTER_SIZE` constants. The data carries its own grouping signal.

3. *Layer 3 dynamism beats hardcoded grouping.* The TQQQ case (open burst vs midday isolated events) requires session-phase awareness, news context, and cross-symbol coherence to narrate well. None of that lives in a fixed rule. An LLM reading the day's narrations can synthesize correctly without a maintained per-scorer time threshold.

4. *Per-event narrations stay ground truth.* Anyone clicking through to investigate a specific event sees an unmerged narration. Repetition is handled at the *presentation* level (Layer 3 synthesis at the top of the daily page, optional UI grouping in the per-event list), not by destroying data.

**Implementation plan.**

Phase A: Build `EnrichWithCoOccurrenceActivity` (~2 hr)
- New activity slots between `ScoreEventsActivity` and `SelectTopEventsActivity` in `ScoreWorkflowImpl`.
- For each scored event above an interest floor (e.g., score in top percentile within its scorer), query other-scorer scored_events on the same symbol whose intervals fall inside the parent's `[ts, ts_end]`.
- Aggregate summary stats per scorer type ({count, total_shares, median_lifetime, etc.}) into a `co_occurring` block on the parent's breakdown.
- Set `subsumed_by_event_id` on each child row so `SelectTopEventsActivity` skips them.
- Idempotent: pre-clean by trading_date.

Phase B: Delete the disabled combine code (~10 min)
- Remove `CombineRelatedEventsActivity` + `Impl`, `CombineWorkflow` + `Impl`.
- Remove their `WorkerMain` registrations.
- Remove the temporal-design.md + roadmap references.

Phase C: Build Layer 3 daily synthesis (queued, ~half day)
- New `SynthesizeDayWorkflow` runs after narration completes.
- One LLM call per day. Input: list of all the day's narrations + day metadata (date, session phases, etc.). Output: a single "today's themes" paragraph.
- Slot at top of the daily page in the vedanta-systems frontend.

**Open follow-ups.**

- *Decide enrichment's "interest floor."* Probably the top percentile (same threshold as selection) — only events selected for narration get enriched. Saves compute. Alternative: enrich everything in scored_events. Decide during build.
- *Co-occurring scorer-type pairs.* Should a sweep's co_occurring include sweeps on the same symbol? Probably no (would double-count if both are selected); cross-scorer only. Confirm via test data.
- *Cross-symbol enrichment.* Out of scope for now. Eventually: when AAPL is halted, AAPL-derivative ETF activity is relevant context. Requires correlated-symbol table (we don't have).
- *Verifier extension for `co_occurring` block.* The verifier already walks all JSON values via `appendAllValues()`, so numbers in `co_occurring.during_event.post_cancel_clusters` are in the haystack. Should work without changes. Confirm during build.

---

## 2026-05-18 — `order_lifecycle` materialized table between Parse and Score

**Context.** Two of the seven event scorers — `PostCancelClusterScorer` and `LayeringScorer` — both ask the same underlying question: *"for every order placed on the book today, when (if ever) was it cancelled, and what was the gap?"* Both implementations queried `orders_add ⨝ orders_delete ON order_id` at scoring time, then post-filtered to lifetime < 50 ms.

That JOIN became the bottleneck. On the 2026-05-08 trading day, `orders_add` has 161 937 752 rows and `orders_delete` has 160 364 854 rows. The resulting hash table is ≈ 13 GB. The two scorers each ran the same JOIN independently, paying the cost twice per run. Postgres's `work_mem` defaulted to 32 MB per parallel worker, so the hash partitions spilled to ~9 GB of temp files on disk; the activity was I/O-bound on `DataFileRead` with the 32-core box mostly idle.

Three iterations of tuning made the spill smaller but never eliminated it:

| Tuning | `mem_limit` / `shared_buffers` / `work_mem` | PostCancel runtime | Spill |
|---|---|---|---|
| baseline | 4 GB / 1 GB / 32 MB | ~25 min | ~9 GB random-access |
| v1 (session work_mem) | 16 GB / 4 GB / 1 GB | ~20 min | ~9 GB sequential |
| v2 (aggressive) | 48 GB / 8 GB / 2 GB + `hash_mem_multiplier=2` | ~15 min | ~9 GB still |

The 13 GB hash table simply does not fit cleanly in any reasonable per-worker memory budget. We were tuning around the wrong-shape query.

**Decided.** Stop recomputing a fixed answer on every scoring run. An order's lifecycle (add timestamp paired with terminal event) is a fact about a trading day, not a derivation. Build it once.

- New activity `MaterializeOrderLifecycleActivity` runs between Parse and Score in `DailyPipelineWorkflow`. Idempotent via pre-clean by `trading_date`.
- New hypertable `order_lifecycle` — one row per order with `(trading_date, symbol, order_id, side, add_ts, add_ts_nanos, add_price_raw, add_size, delete_ts, execute_ts, terminal_state, lifetime_ns, feed_source)`.
- `terminal_state ∈ {'deleted', 'executed', 'open'}`. Orders that survive end-of-session land with `lifetime_ns = NULL`, `terminal_state = 'open'`.
- Partial index on `(trading_date, symbol, add_ts) WHERE lifetime_ns IS NOT NULL AND lifetime_ns < 100_000_000` — the 99 %+-of-rows-excluded filter is baked into the index, so PostCancel + Layering hit a sub-second sequential range scan instead of a full-table scan.
- `PostCancelClusterScorer` + `LayeringScorer` rewritten to read this table. Both queries become `SELECT … FROM order_lifecycle WHERE trading_date = ? AND terminal_state = 'deleted' AND lifetime_ns ≤ 50_000_000 ORDER BY symbol, side, add_ts_nanos`.
- Standalone `MaterializeWorkflow` for ad-hoc replay (mirrors `ScoreWorkflow`).
- Session-level `work_mem` bump in `ScoreEventsActivity` reverted from 2 GB → 256 MB, since scoring is no longer JOIN-heavy.

**Why this is right and not just "different".**

1. *Matches the conceptual model.* "An order has a lifecycle" is how a trader thinks. The original schema is wire-format-shaped (one row per protocol message); the new table is domain-shaped.
2. *JOIN cost is amortized.* PostCancel + Layering today, plus any future order-lifecycle scorer (e.g. `TimeInBookDriftScorer`), pay once instead of N-times-per-tuning-iteration.
3. *Unlocks the 30-day backfill.* Inter-day scorers (`VolumeDeviation`, `TimeInBookDrift`) need 30 trading days of history. 30 × 45-min-per-scoring-run is ≈ 22 hours of compute. With the lifecycle table: 30 × ~2 min (materialize once + fast scoring) ≈ 1 hour. Two orders of magnitude.
4. *Reverts memory pressure.* The 48 GB `mem_limit` we set during tuning becomes unnecessary; we can drop back to something modest after observing the materialize step settles.
5. *No change to wire-format tables.* `orders_add` / `orders_delete` / `orders_executed` stay as the raw ingest target — validators still cross-check against them, the parser doesn't change. The new table is a *derivation*, transparently rebuilt from the raw rows by an idempotent activity.

**What was considered and rejected.**

| Alternative | Why rejected |
|---|---|
| **More aggressive Postgres tuning** | Three iterations didn't eliminate spilling; the 13 GB hash table is fundamentally too big for any reasonable per-worker work_mem. |
| **Rewrite as `LAG()`-style single-pass scan** | Possible, but more invasive (touches both scorers' algorithms) and doesn't help any future scorer that also wants paired add/delete data. |
| **Run PostCancel + Layering concurrently** | Both query the same JOIN; running them in parallel competes for the same hash table memory and worsens spilling. |
| **Emit lifecycle rows from the parser directly** | The parser's `OrderBook` already pairs Add ↔ Delete at parse time. We could emit `order_lifecycle` rows during the parse pass and skip the JOIN entirely. *This is the absolute best end-state*, but it touches `TimescaleWriter` + breaks the "one table per wire message" convention. The activity-level materialization captures 95 % of the win for 10 % of the effort, so we deferred the parser-side emission to a follow-up. Logged in `docs/todo.md`. |
| **Materialized view (auto-refresh)** | Postgres MVs need explicit `REFRESH` and don't fit cleanly into Temporal's idempotent-activity model. The activity gives us better failure semantics + replay. |

**Engineering bug caught during the build (worth recording).** First draft of `MaterializeOrderLifecycleActivityImpl.doMaterialize()` used `LEFT JOIN LATERAL (SELECT … FROM orders_executed WHERE ox.order_id = a.order_id ORDER BY ts DESC LIMIT 1) ON true`. That's per-row — 162 M outer rows × indexed lookup each ≈ 30–270 minutes. Replaced with a CTE that builds "last execute per (order_id, feed_source)" once over the small (~2.4 M row) `orders_executed` table via `DISTINCT ON`, then a regular hash JOIN against `orders_add`. Textbook fix for an N+1-shaped SQL.

**Open follow-ups recorded in `docs/todo.md`.**

- Parser-side lifecycle emission (eliminates the JOIN entirely; deferred as the larger architectural improvement).
- TimescaleDB columnar compression on chunks older than 7 days (after the 30-day backfill).
- Dropping `symbol` from the residual JOIN predicate in the materialize step (per-session uniqueness of `order_id` is already guaranteed by IEX's DEEP+ spec; symbol equality is redundant).

---

## 2026-05-11 (late) — Code identifiers use DPLS (filename token), docs/prose use DEEP+ (product name)

**Context.** Two names for the same feed: the spec / product name is **DEEP+**, the HIST filename token is **DPLS**. We had been using "DeepPlus" in Java code (package `com.longexposure.deepplus`, classes `DeepPlusMessage*`, enum `Feed.DEEPPLUS`, variable `deepPlusFile`), which was asymmetric with the 4-letter `Tops` / `Deep` siblings and grated to read.

**Decided.** Rename all code-internal references to use **`Dpls`** / **`DPLS`** / **`dpls`** (matching the HIST filename token). User-facing prose (README marketing line, narrative copy) keeps **DEEP+** since that's the canonical product name IEX uses and it has stronger SEO/recognition (people search for "DEEP+ parser"). Docs reference DEEP+ as the product, with a one-line equivalence note at the top of `AGENTS.md` and `docs/protocol-notes.md`.

**What changed in code.**
- Package: `com.longexposure.deepplus` → `com.longexposure.dpls` (12 files)
- Sealed marker: `DeepPlusMessage` → `DplsMessage`
- Router: `DeepPlusMessageRouter` → `DplsMessageRouter`
- Validators: `DeepPlusBboCrossValidator` → `DplsBboCrossValidator`, `DeepVsDeepPlusValidator` → `DeepVsDplsValidator`
- Test: `DeepPlusMessagesTest` → `DplsMessagesTest`
- Enum constant: `Feed.DEEPPLUS` → `Feed.DPLS`; display label `"DEEP+"` → `"DPLS"`
- All identifier-cased forms (`deepPlusFile`, `onlyDeepPlus`, etc.) → `dpls*`
- All javadoc references `DEEP+` → `DPLS` inside code

**What stayed DEEP+.**
- README marketing line: "the first open-source IEX DEEP+ parser in any language" — the product name people will search for.
- README "Data feed selection" section: introduces DEEP+ as the product, with the DPLS-is-the-filename-token explanation alongside.
- Historical entries in this log: prior decision entries keep their original phrasing.

**Why this matters.** Naming consistency in code is load-bearing for readability — `Tops`, `Deep`, `Dpls` reads as three siblings; `Tops`, `Deep`, `DeepPlus` reads as two-and-an-outlier. The product name in marketing copy is also load-bearing for discoverability — "DEEP+" has years of IEX documentation behind it; "DPLS" does not. Splitting code-vs-prose lets each optimize for its own constraint.

---

## 2026-05-11 — Pivot: DEEP+ is the v1 product; TOPS becomes the validation oracle

**Supersedes the 2026-05-10 "TOPS v1 / DEEP+ phase 2" decision below.** The reasoning in that entry still applies in isolation, but a load-bearing assumption — "each feed is 2–3 weeks of work" — was invalidated by today's execution pace.

**What changed.** On 2026-05-11 we compressed Days 1–10 of the README plan into roughly one working day:
- pcap-ng + libpcap reader (~270 LOC, no native deps)
- IEX-TP transport decoder
- All 7 admin message decoders + sealed `AdminMessage` interface
- All 5 TOPS-specific trading decoders + `TopsMessageRouter`
- 48 passing unit tests
- Postgres + TimescaleDB schema with 8 hypertables + continuous aggregate
- COPY-based `TimescaleWriter`
- End-to-end run: 9.5 GB 2026-05-08 HIST → 294,790,405 messages written in 22:27 min

The morning's "phase 2 = 2–3 week post-launch sprint" was already an estimate, not a measurement. Given actual velocity, the DEEP+ parser is a 1–2 session sprint — comparable to one day's TOPS work. **Treating TOPS as a separate v1 launch with DEEP+ deferred no longer makes sense.**

**Decided.**

- **v1 = DEEP+** (DPLS feed). All scoring, narration, UI work targets DEEP+ event types from day 1.
- **TOPS code stays.** It's repurposed as a **validation oracle**: same trading day, parse both feeds, derive TOPS-equivalent BBO from DEEP+ book state, diff against the actual TOPS feed. Where they disagree, the DEEP+ decoder has a bug.
- **The 7.75M trades + 285M quotes already loaded in Postgres are not thrown away** — they become the cross-check reference data for DEEP+ decoder correctness.
- **Positioning shifts** from "reference implementation of the IEX TOPS parser in Java" to **"first open-source reference implementation of IEX DEEP+ in any language."** Verified 2026-05-11: no public DEEP+ parser exists in any language (GitHub search results below). This is a genuine community contribution.

**Why the velocity argument is load-bearing.**

The morning's 7 reasons for TOPS-first reduce to two structural concerns when velocity is fast:

1. **Validation difficulty for DEEP+** — book-state reconstruction has no analogous "compare to a published number" check.
2. **No reference parser exists** for DEEP+ in any language.

Both are real but **both are mitigatable**, and the mitigation is exactly the TOPS work we already did:
- The same trading day's TOPS feed serves as the reference. Derive top-of-book from the DEEP+ order-book state machine and compare to the canonical TOPS Quote Update / Trade Report stream. Per-symbol per-second BBO must match. Per-symbol total trade size + count must match.
- We already have the TOPS infrastructure (parser + writer + 285M loaded quotes for 2026-05-08). Reusing it as a validation oracle is essentially free.

The five other morning-of reasons (stepping-stone reuse, ship risk, LLM prompt iteration time, spec maturity, reputation narrative) either no longer apply at our pace or now favor the pivot:
- Stepping-stone reuse: confirmed today. ~60% of parser LOC is in the shared `admin/` + `wire/` + `transport/` + `pcap/` packages and works unchanged for DEEP+.
- Ship risk: lower at our pace than feared.
- LLM iteration: we want the scorer designed against DEEP+ event types from day 1 (richer narrative surface) rather than re-design after a TOPS-only v1 launch.
- Spec maturity: DEEP+ 1.02 spec is excellent and we've read it cover-to-cover.
- Reputation: "first open-source DEEP+ parser, with a public narrative product on top" is strictly better than "shipped a generic TOPS-only narrative, then added DEEP+."

**GitHub search verification (2026-05-11).** Queries `IEX DEEP+ parser`, `IEX DPLS`, `iex order book parser` — all return either zero results or only TOPS/DEEP parsers from years ago. The 5 existing parsers (`WojciechZankowski/iextrading4j-hist` Java, `rob-blackbourn/iex_parser` Python, three C++ DEEP parsers, `B1tWhys/iextool` Python) all predate the Jan 2025 DEEP+ spec publication and don't support its trading-message set.

**What this changes in the codebase / docs.**

- `docs/plan.md` — phase 2 section reframed as "next sprints"; Day 11+ moves to DEEP+ implementation.
- `README.md` — "reference implementation of the IEX TOPS parser" → "first open-source IEX DEEP+ parser." TOPS demoted to "validation oracle." Feed-selection section reframed.
- `AGENTS.md` — "touching feed-handling code" guardrail flipped (DEEP+ is v1; TOPS is the validator).
- `docs/todo.md` — start-of-next-session checklist now leads with "download a DPLS .pcap.gz."
- Source code layout (`com.longexposure.tops.*` for TOPS, future `com.longexposure.dpls.*` for DEEP+) is unchanged — same per-feed package convention, just different priority order.

**Open risks (unchanged from morning analysis, accepted).**

- No reference parser to cross-check against. Mitigated by the TOPS cross-validation above.
- DEEP+ history only back to Jan 2025 (~16 months). Fine for narrating yesterday + 30-day baselines; rules out multi-year analysis.
- Book-state validation is structurally harder than trades-totals validation. We'll likely need a SNAP-equivalent reconstruction comparison as a tertiary check eventually.

**What's still open.** When this entry was written we hadn't yet downloaded a DPLS file. First action next session: pull the 2026-05-08 DPLS HIST file (same date as the TOPS data already loaded) so cross-validation is a same-day same-symbols comparison.

---

## 2026-05-10 — TOPS for v1, DEEP+ (not DEEP) for phase 2; SNAP feeds out of scope

**Context.** The README originally specified TOPS-only for v1 with DEEP earmarked for phase 2, citing file sizes (TOPS "few hundred MB", DEEP "13 TB across full history"). Pulling real numbers from the HIST API for recent dates showed all three feeds (TOPS, DEEP, DEEP+/DPLS) are within 5% of each other in compressed size — ~7 GB/day in 2025, ~2.9 GB/day in 2024. The original size-based argument for TOPS doesn't hold; the choice has to rest on parser complexity, validation difficulty, and ship risk instead.

After pulling all 7 spec PDFs from `~/workspace/data/long-exposure/specs/` and reading the trading-message sections of TOPS 1.66, DEEP 1.08, and DEEP+ 1.02:

**Decided.**

- **v1: TOPS 1.6 only.** Single feed, single parser path, validate against IEX's published daily totals.
- **Phase 2: DEEP+ (skip DEEP entirely).** Order-by-order book, every individual displayed order tracked through its Add/Modify/Delete/Execute lifecycle.
- **SNAP feeds: permanently out of scope.** TOPS SNAP / DEEP SNAP / DEEP+ SNAP are request-response TCP services for live consumers joining mid-day; they don't appear in HIST (which is what we read). Not relevant to a T+1 pipeline.
- **Phase 2 is not a multi-month delay.** Target it as a 2–3 week follow-up sprint after v1 ships — day ~22 ships TOPS publicly, day ~40 ships DEEP+ alongside it.

**Why TOPS for v1 (and not jumping straight to DEEP+).**

1. *Validation difficulty differs qualitatively.* TOPS validates trivially: sum trade volumes per symbol, compare to IEX's daily totals. A decoder bug fails loudly. DEEP+ validation requires book-state reconstruction at sampled timestamps and per-order lifecycle correctness — and there is no analogous "compare to a published authoritative number" check. A subtly broken DEEP+ decoder silently produces wrong book state, and narratives confidently lie. This is the scariest bug class in market data parsing.

2. *TOPS is a stepping stone, not throwaway.* ~60% of the parser code (transport, admin decoders, framing, gap handling, Postgres writer skeleton, Temporal pipeline, LLM narration loop, Caddy/deploy/UI wiring) is shared across all three feeds. v1 = build the shared layer + TOPS trading decoders. Phase 2 = add DEEP+ trading decoders + order book state machine + order-narrative templates. No throwaway work.

3. *Ship risk.* TOPS-at-day-22 gives a near-certain shippable product. DEEP+-by-day-22 carries meaningful probability of "half-shipped" or "didn't ship." For a public, reputation-attached project, variance dominates expected value.

4. *LLM prompt iteration needs working parsed events.* Days 18–19 are prompt tuning. If the parser isn't producing real events by then, prompts don't get tuned. TOPS-running-by-day-7 leaves weeks for prompt iteration.

5. *Spec maturity.* TOPS 1.66 dated Oct 2021, stable, multiple reference implementations exist (open-source IEX parsers in Python/Go/Rust). DEEP+ 1.02 dated Jan 2025; thin ecosystem, fewer references to cross-check decoders against.

6. *Reputation narrative.* "Shipped v1 in 22 days, shipped order-by-order v2 three weeks later" reads as judgment + execution. "Attempted DEEP+ and got 80% of the way" reads as ambition without execution. The former is a strictly better story even if total code written is similar.

**Why DEEP+ (not DEEP) for phase 2.**

Information-theoretic: **DEEP+ ⊃ DEEP ⊃ TOPS**. DEEP+ carries every individual displayed order's lifecycle (Add/Modify/Delete/Execute by Order ID). Aggregating order sizes by `(symbol, side, price)` derives DEEP-equivalent price levels for free. So DEEP is a stopping point we'd throw away — once we've invested in book reconstruction, the marginal cost to track individual orders is small.

Narrative value: DEEP+ unlocks order-lifecycle stories ("8 orders posted and cancelled within 50ms — classic spoof shape"; "median order time-in-book on SPY collapsed from 800ms to 90ms") that map directly to IEX's transparency brand. DEEP only unlocks depth-of-book narratives, which are less distinctive.

**Why SNAP feeds are out of scope.**

SNAP (TOPS/DEEP/DEEP+) is a TCP request-response service used by live consumers who joined the multicast mid-day and need to recover the current order book state. Read of `deep-plus-snap-1.03.pdf` confirms: SnapshotRequest → SnapshotStart/SnapshotData(...)/SnapshotEnd response carrying the latest admin messages + Add Orders needed to rebuild book state at a given Sequence Number. Auth-gated, 1000-requests/day quota, credentials via Market Ops.

Long Exposure consumes complete daily .pcap.gz files from HIST T+1. Every message is in correct sequence in the file; mid-day recovery isn't a concept that applies. The SNAP specs stay archived at `~/workspace/data/long-exposure/specs/` for completeness but won't be implemented.

**What this changes in the codebase.**

- README's "Alternatives considered" → DEEP section becomes a DEEP+ section; size claims corrected to ~7 GB/day per feed (was "few hundred MB" / "13 TB").
- `docs/plan.md` Day 22 phase 2 note → DEEP+, with the 2–3 week follow-up framing.
- `docs/protocol-notes.md` already updated with real spec content (TOPS/DEEP/DEEP+ trading message tables).
- Architecture work that needs to be DEEP+-ready from day 1:
  - `DownloadHistActivity(date, feed_name, version)` — parameterized
  - `events` hypertable has a `feed_source TEXT` column from initial schema
  - Parser package structure: shared `transport/` + `admin/` + per-feed `tops/` (later `dpls/`)

**Confidence + what's still open.**

High confidence on the v1 = TOPS decision. The "skip DEEP, go straight to DEEP+ in phase 2" call is opinionated and could be revisited if any of these turn out differently than expected:

- If TOPS daily-totals validation reveals decoder bugs that take longer than ~1 week to chase, phase 2 may slip; DEEP+'s harder validation surface is a bigger version of the same problem.
- If real `*.pcap.gz` files exhibit framing edge cases (truncated packets, gap-fill artifacts) we haven't anticipated, the shared transport layer might absorb more time than planned and the per-feed work could compress.
- If, after seeing real TOPS narratives at launch, the depth-of-book signal feels worth the extra integration cost, we could land DEEP between v1 and DEEP+ as an intermediate milestone — but this is unlikely given the strict information-superset argument.

**Addendum 2026-05-10 (later) — DPLS = DEEP+ access confirmed, history depth caveat, reference implementation availability.**

After publishing the decision above, three additional pieces of information were gathered that confirm the plan but add nuance:

1. **DPLS = DEEP+.** The HIST filename token is `DPLS` (URL/filename-safe; "+" is awkward in filenames). The product/spec name is "DEEP+". Same wire format, same Message Protocol ID `0x8005`. Filenames follow `YYYYMMDD_IEXTP1_<FEED><VERSION>.pcap.gz` so the slot uses `DPLS`. Convergent evidence: DPLS first appears in HIST listings in Jan 2025, exactly aligned with the DEEP+ spec publication date.

2. **HIST access is free and identical for all three feeds.** No additional authentication or quota beyond what TOPS / DEEP already require (none). Same JSON API at `https://iextrading.com/api/1.0/hist`, same Google Cloud Storage download URLs.

3. **DEEP+ history depth is limited to ~Jan 2025 onward.** TOPS and DEEP go back to 2017; DEEP+ has ~16 months as of the project start. Implications:
   - 30-day rolling baseline and 30-day backfill (what the scorer needs): well within DPLS available history. **No impact on v1 or phase 2.**
   - Multi-year historical analysis with DEEP+ is not possible. Would require falling back to TOPS+DEEP for pre-2025 data. The project doesn't plan multi-year analysis, so this is a constraint to record but not a blocker.
   - Phase 2 launch positioning: DEEP+ can't claim "years of order-by-order history" — only "everything DEEP+ has published." Still a real product, but the marketing changes from "deep history" to "deep granularity."

4. **Reference implementations available.** GitHub search turned up several open-source IEX parsers:
   - `WojciechZankowski/iextrading4j-hist` (Java, 22 stars, Jun 2023) — TOPS + DEEP. **Same language as us; primary cross-check target.**
   - `rob-blackbourn/iex_parser` (Python, 29 stars, Jan 2022) — TOPS + DEEP. Most-starred; useful for cross-language validation.
   - Three C++ implementations covering DEEP (`Anirudhsekar96/IEX_DEEP_HISTORICAL_DATA_PARSER`, `kushal-goenka/iex-pcap-parser`, `dhsilv/iex_deep_parser`).
   - `B1tWhys/iextool` (Python, small CLI).
   - **No existing parser for DEEP+** in any language as of search.

   Cross-check strategy for v1: parse a sample TOPS .pcap.gz with `iextrading4j-hist`, dump the message stream, run our parser against the same file, diff the outputs message-by-message. Combined with the daily-totals validator this gives us two independent correctness checks.

   For phase 2: no reference parser exists for DEEP+, so we'd be flying solo on decoder correctness. This is the strongest additional reason to ship TOPS first — TOPS work matures our shared decoder + validation infrastructure, so when DEEP+ work begins we already trust the surrounding layers and can focus purely on the new trading-message decoders and order-tracking state machine. It also positions Long Exposure as **the open-source reference implementation for DEEP+ in Java** — which directly reinforces the README's "reference implementation" framing for IEX.

5. **Bootstrap and "30-day baseline" clarified.** The scorer flags events as "unusual" by comparing to per-symbol rolling 30-trading-day averages of several metrics (daily volume, daily trade count, avg spread, halt frequency, intraday volume distribution). TimescaleDB continuous aggregates maintain these incrementally. On day 1 of launch we have zero history in DB; the bootstrap is: parse the previous 30 trading days of HIST files → ingest → baselines populate → re-score and re-narrate each of those 30 days with their now-defined baselines. Launch day shows 30 days of populated narrated archive. The same 30-day backfill is the bootstrap *and* the visible launch content. See @plan.md Days 11–13.

**Recorded URLs (for reproducibility).**

- HIST API endpoint: `https://iextrading.com/api/1.0/hist` (returns all dates) or `?date=YYYYMMDD` for one date.
- HIST file naming convention: `YYYYMMDD_IEXTP1_<FEED><VERSION>.pcap.gz` where FEED ∈ {TOPS, DEEP, DPLS}.
- IEX market data landing page: `https://iextrading.com/trading/market-data/`
- Spec PDFs (canonical IEX pages and the CDN URLs of the actual PDFs):
  - TOPS 1.66: `https://www.iex.io/documents/tops-v1-66`
  - TOPS 1.5: `https://www.iex.io/documents/tops-v1-5`
  - DEEP 1.08: `https://www.iex.io/documents/deep-v1-08`
  - DEEP+ 1.02: `https://www.iex.io/documents/iex-deep-plus-specification`
  - TOPS SNAP: `https://www.iex.io/documents/iex-tops-snap-specification` (out of scope)
  - DEEP SNAP: `https://www.iex.io/documents/iex-deep-snap-specification` (out of scope)
  - DEEP+ SNAP: `https://www.iex.io/documents/deep-plus-snap-specification` (out of scope)
- Reference parsers (sorted by relevance):
  - `https://github.com/WojciechZankowski/iextrading4j-hist` (Java, primary cross-check)
  - `https://github.com/rob-blackbourn/iex_parser` (Python, most popular)
  - `https://github.com/Anirudhsekar96/IEX_DEEP_HISTORICAL_DATA_PARSER` (C++ DEEP)
  - `https://github.com/dhsilv/iex_deep_parser` (C++ DEEP, most recent)

Local archived copies of all 7 spec PDFs live at `~/workspace/data/long-exposure/specs/`.

---

## 2026-05-10 — No frontend in this repo; surface UI through vedanta-systems

**Context.** The original Day-1 scaffold (commit `8079d64`) included a `frontend/` directory containing a Svelte 5 SPA, an nginx prod image, a Vite dev image, and a public Cloudflare hostname (`longexposure.vedanta.systems`) routed through the workspace Caddy. This violates the workspace pattern.

**The pattern.** Personal projects under `~/workspace/dev/` ship **only an API**. The unified portal at `~/workspace/dev/vedanta-systems/` (React + TypeScript + shadcn/ui, public at `vedanta.systems`) hosts a per-project browser component that consumes `/api/<project>/*` via its own nginx. found-footy and spin-cycle already follow this shape; no separate frontends exist in either repo. There is one Cloudflare tunnel for the whole portal, not one per project.

**Decided.**

- Delete the entire `frontend/` directory.
- Strip the frontend service from `docker-compose.yml` and `docker-compose.dev.yml`, including `VITE_API_BASE_URL`, the frontend bind-mount volume, and the `frontend-node-modules` named volume.
- Drop `VITE_API_BASE_URL` from `.env.example`.
- Drop the `long-exposure-{prod,dev}-frontend.luv` Caddyfile entries and the entire `longexposure.vedanta.systems` Cloudflare ingress + DNS CNAME from `deploy/INFRA-NOTES.md`. The API alone joins `proxy`.
- Rewrite the README's service-layout table, frontend section, repo-layout list, and Day-22 line.
- Day-22 work is now: nginx route + `long-exposure-browser.tsx` + App registration in `vedanta-systems`, plus production bring-up here.

**Why.** N per-project frontends means N tech stacks, N Cloudflare tunnels, N deployments, and a fragmented UX. The portal pattern is already established and working for found-footy + spin-cycle. The original scaffold appears to have been generated from the README in isolation without checking the workspace conventions.

**What's still open.** Frontend design conventions (component naming, theming, dark-mode default, score-breakdown disclosure UX) need to be decided when the `long-exposure-browser` component is actually built. Cross-reference the existing `found-footy-browser` and `spin-cycle-browser` for shape.

**Memory.** Saved as a feedback memory at `~/.claude/projects/-home-vedanta-workspace-dev-long-exposure/memory/feedback_no_frontend_in_project_repos.md` so future sessions don't repeat the mistake on this or the next project.

---

## Project-genesis decisions (carried forward from README, dated to project start: 2026-05-10)

These are the load-bearing structural choices already documented in detail in @../README.md. Captured here in summary form so this log is the canonical decision archive going forward.

### Database: Postgres 16 + TimescaleDB

Chosen over QuestDB, DuckDB, ClickHouse, InfluxDB, VictoriaMetrics, kdb+. Full alternatives analysis lives in @../README.md ("Alternatives considered (database)"). Summary: Postgres+Timescale wins on operational maturity, ecosystem, and migration tooling. The ~10–20% time-range scan and ~2–5× ingest gap vs QuestDB is irrelevant at our data scale (a few hundred MB/day, ingested once nightly).

### Data feed: TOPS only

Chosen over DEEP (depth-of-book) and DPLS (order-by-order). TOPS contains every event type Long Exposure needs (halts, trades, quotes, status, system events) and is a few hundred MB compressed per day. DEEP is ~13 TB across full history. Phase-2 candidate if liquidity-depth analysis becomes worth the cost.

### Parser language: Java 21 + Gradle Kotlin DSL

Java for pcap4j (best-of-breed pcap library), JDBC (mature Postgres ecosystem), and Temporal SDK quality. Kotlin DSL for the build because the type system surfaces config errors at evaluation time. Multi-stage Dockerfile (gradle JDK21 builder → JRE alpine + libpcap) so dev needs no host-side JDK install.

### Workflow orchestration: Temporal

Chosen for durable workflow execution, automatic retries, activity-level fault isolation, and full execution history. Each pipeline stage is an activity. Long-running activities heartbeat to avoid spurious retries. Temporal metadata lives in its own Postgres (separate from the events DB) per the upstream image's recommendation.

### LLM: Qwen3.5-122B on `llama-large.joi`

Existing homelab infrastructure on `joi`. The honest justification is data sovereignty + zero-marginal-cost iteration on prompts, plus the homelab-systems showcase. (Cost savings are rounding error at 5–50 narrations per day.) The 122B model produces materially better financial prose than smaller models — that part *does* matter for the user-facing output.
