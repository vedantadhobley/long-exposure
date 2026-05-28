# TODO

Project scratchpad, agent + human editable. Cross-references @plan.md (sprint-by-sprint history), @decisions.md (architectural rationale), and @scoring-and-narration.md (the scoring + narration design spec).

Last refresh: 2026-05-25.

> **🚀 Active sprint: see [`launch-sprint.md`](launch-sprint.md)** — 10-day plan, 2026-05-21 through 2026-05-30, ending in Monday 2026-06-01 publication. Whitepaper writing happens Sunday in parallel; IEX day 1 is Tuesday 2026-06-02. This `todo.md` continues to track the rolling open items + post-launch backlog; `launch-sprint.md` is the explicit work plan with daily milestones and acceptance criteria.

> **Naming note (2026-05-22).** Older entries below use "Layer 0/1/2/3/4" pipeline-stage vocabulary, now deprecated. Map: Layer 1 = **DETECT**, Layer 2 = **DESCRIBE**, Layer 3 = **SYNTHESIZE**, Layer 4 = **AGGREGATE**, new per-event interpretation pass = **INTERPRET**. See [`pipeline-architecture.md`](pipeline-architecture.md) for canonical vocabulary.

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

## What's next, in order (revised 2026-05-22)

### Immediate (in order)

1. ✅ **Co-occurrence enrichment** (2 hr — done 2026-05-20).
2. ✅ **Delete the retired combining code** (done 2026-05-20).
3. ✅ **Sampling params: Qwen3.5 published recommendations** (done 2026-05-20) — EXTRACT + RENDER presets. SYNTHESIZE preset added 2026-05-22.
4. ✅ **Verifier dotted-path fix + v4 prompt** (done 2026-05-20).
5. ✅ **DETECT derived-field enrichment** (done 2026-05-22) — all 7 scorers now pre-compute the analytical ratios (orders_per_level, duration_pct_of_regular_session, etc.) the LLM would otherwise compute at inference time. Eliminated the arithmetic-error failure mode by construction.
6. ✅ **`Humanize` → `BreakdownFmt` + `Enrich` → `SymbolFields` rename pass** (done 2026-05-22) — class names now reflect actual responsibilities; `round2` deleted in favor of explicit `round(v, 2)`.
7. ✅ **INTERPRET production wiring** (done 2026-05-22) — `InterpretEventActivity` + `InterpretWorkflow` + `InterpretationVerifier` + `TradeWindow` helper + `interpretations` table. v5 prompt at **98.78% verifier-passed** on 2026-05-08 (5 iterations chasing rounding, unit-conversion, ticker-presence, approximation patterns). Full design in [`interpretation-design.md`](interpretation-design.md).
8. ✅ **SYNTHESIZE production wiring** (done 2026-05-22) — `SynthesizeDayActivity` + `SynthesizeDayWorkflow` + `SynthesisVerifier` + `daily_synthesis` table + `SamplingParams.SYNTHESIZE` (Qwen reasoning preset). End-to-end on 2026-05-08: paragraph published, ticker check clean, 1 magnitude-approximation warning.
9. ✅ **Small-batch backfill (week 05-18→05-22)** — done 2026-05-23/24. Cross-day robustness confirmed: DESCRIBE 98-100%, INTERPRET 97-100%, zero integrity gaps, 5 syntheses. Audit in conversation; failure-class understanding corrected (see Post-backfill polish below).
10. ✅ **Week-aligned 2-week retention** — done 2026-05-25. `RetentionSweepActivity.weekBoundary()` + unit test; closed the `order_lifecycle`/`scored_events`/`selected_events` gaps. See `decisions.md` 2026-05-25.
11. ✅ **2-week backfill** — both weeks loaded (05-11→15, 05-18→22) + 05-08; re-run uniform on the current formatting/retry (2026-05-27).

### Inter-day work — ✅ DONE (Phase 2 + 3, 2026-05-26/27)

12. ✅ **AGGREGATE — weekly rollup** — done 2026-05-26 (`b06da2b`). `AggregateWeekActivity`/`Workflow` + `weekly_aggregate` table; recompute-daily "week-so-far" + prior-8-week trend window + content-addressed skip; wired into `DailyPipelineWorkflow` after SYNTHESIZE. Dotted-ticker fix shared via `SynthesisVerifier.extractTickers` (`b64d26f`). Prompt v4 (anti-cross-sum + no-priors guard).
13. ✅ **Inter-day scorer `VolumeDeviationScorer`** — done 2026-05-26 (`6282090`/`f34bd17` + `e531d26`). Reads the `daily_volume_by_symbol` cagg (400-day window) via `BaselineProvider`/`CaggBaselineProvider`; `RefreshBaselinesActivity` keeps the cagg current before scoring. "Nx the trailing median." Validated: MEHA 22.2×, IMRX 14.5×.

### Frontend — the launch critical path (Days 7-8, NOT started)

14. **API + browser wiring** in `vedanta-systems` — routes for synthesis / interpretation / drill-down; `long-exposure-browser` consuming them. **Must pick latest-per-`selected_id`** (the `narratives`/`interpretations` tables carry rows from every prompt-version iteration — 2,560 narrative rows across 6 days; the SYNTHESIZE loader's `DISTINCT ON` is the pattern to mirror).
15. **The three wow items** (agreed 2026-05-25): **#1** intraday price/volume chart with event markers (the "long exposure photograph" — highest wow); **#2** visible-grounding badge ("every figure traces to IEX data" → expandable breakdown — the verifier is the moat, show it); **#4** recursive drill from a narration to its raw atomic events. If the frontend overruns, #1 and #2 are must-keep; #4 is the first to drop.
16. **Prod bring-up + dev→prod copy** — copy only the narrative-layer tables (narratives/interpretations/daily_synthesis/weekly_aggregate/selected_events/scored_events/symbols/pipeline_runs/validation_runs — kilobytes, no re-parse, preserves the audited prose). Wire tables stay in dev / accumulate in prod via cron. Procedure → `operations.md`.

### Pre-relaunch — Tier A/B/C audit (2026-05-28)

Everything that must be **solid before the 10–12 hr overnight relaunch** (the relaunch bakes the finalized analytics fields + rollups + new prompts into the whole 2-week dataset; any bug here becomes a re-run to undo). Updated 2026-05-28 after the comprehensive audit (commits `8cc84f6`, `ef49eb3`, `d8a3f45`).

**✅ Already cleared:**
- [x] **One-day analytics test** (was item A) — `b0igiuluz` ran Narrate → Interpret → SynthesizeDay on 05-22 end-to-end with the v4 narration-set + v8 INTERPRET + v5 SYNTHESIZE: 163/163 + 160/160 + 1/1 verifier-passed; every new analytic landed (slippage, effective spread, order-to-trade, depth-from-touch, display-ratio, refill-cadence, %-of-book + recovery, robust-z, burstiness, two-sidedness, VPIN). 62 min/day total.
- [x] **Relaunch driver handles weekly cascade** (was item B) — `scripts/rerun-dataset.sh` per-day chains Score → Narrate → Interpret → SynthesizeDay, then per-week `AggregateWeekWorkflow` chronologically.
- [x] **Doc-drift audit** (was item D) — 7 docs cleaned of stale "8 scorers / 22 activities / 9 tables" counts (commit `ef49eb3`).

**🚩 Tier A — Quality fixes, do NOW before relaunch (~45 min):**
- [ ] **A1. Intent-claim denylist in `SynthesisVerifier`** — 05-22's synthesis paragraph contained `"active, fleeting order-book manipulation across leveraged vehicles"` despite the prompt forbidding intent claims; the verifier passed because it only checks numbers + tickers. Add a regex denylist (`manipul`, `spoof*`, `front[- ]?run`, `wash[- ]?trad`, `\bgam(e|ing|ed)\b`, `illegal`, `fake`) so verifier rejects intent words; covers both daily SYNTHESIZE and weekly AGGREGATE (shared verifier). Retry mechanism (3 attempts, temp=1.0 variance) handles rejection cleanly. *(~15 min)*
- [ ] **A2. `PRIOR_WEEKS` 8 → 13** — `AggregateWeekActivityImpl` line 63. Future-proofs the weekly trend horizon to **one full quarter** of context. No runtime change today (only 3 prior weeks in the dataset), but locks in the §8 design widening so future weeks read 13 priors automatically. *(~2 min)*
- [ ] **A3. Pre-compute jargon-anchor strings** — two pockets where the 05-22 prose reads robotic:
   - `burstiness_class` on `PostCancelClusterScorer` + `LayeringScorer`: `"highly bursty"` / `"moderately bursty"` / `"Poisson-like"` based on Fano bands → model writes "highly bursty (Fano 9.4)" not bare "burstiness of 9.43" (37/163 narratives affected).
   - `order_to_trade_phrase` on cluster scorers: when ratio is ∞ (0 fills), pre-render as `"no fills against N posted orders"` → kills the stilted "order-to-trade ratio of infinite" phrasing (9/163 narratives). *(~20 min combined)*
- [ ] **A4. Worker restart** before kicking the relaunch — `TimeInBookDriftScorer` + the lifetime-baseline upsert don't exist in the running JVM (worker started at 01:46:36Z, before commit `509e525`). Schema is auto-applied on worker start, so `daily_lifetime_by_symbol` gets created automatically. *(~30 sec)*

**📋 Tier B — Calendar rollup hierarchy, optional but recommended (~1.5 hr):**

These don't materially affect the relaunch outcome (dormant until enough data accumulates) but get the calendar fractal *done* while we're context-loaded on the rollup design, so when the data arrives there's no human intervention needed. Per `tiered-baselines-design.md` §8 + the calendar reasoning (July 1 = Q3 start; first quarterly fires automatically when 13 weeklies exist ≈ Sept 30; first yearly fires ~Q3 2027).

- [x] **B1. `AggregateQuarterActivity` + `quarterly_aggregate` table** — DONE 2026-05-28 (commit `d4e357c`). Mirror of weekly: reads ≤13 weekly rollups + prior 4 quarterly rollups; recompute-on-week-finalize; content-addressed; gated by `weekly_count >= MIN_WEEKS_FOR_QUARTER` (8) so it sits dormant until enough data. Wired into `DailyPipelineWorkflow` after `AggregateWeekWorkflow`. Validated dormant-path in the 1-day test on 05-22 (returned 0 in <1 sec).
- [x] **B2. `AggregateYearActivity` + `yearly_aggregate` table** — DONE 2026-05-28 (commit `d4e357c`). Mirror of quarterly; reads 4 quarterly rollups; gated by `MIN_QUARTERS_FOR_YEAR=2`. Dormant-path validated in 1-day test.

**🧪 Single-day validation gate (after Tier A applied):**
- [ ] Re-run the 1-day chain on 05-22 with the Tier A fixes loaded (worker restart → `ScoreWorkflow` → `NarrateWorkflow` → `InterpretWorkflow` → `SynthesizeDayWorkflow` → check synthesis paragraph contains no denylist hits + burstiness_class strings + ∞-phrase strings render). If this passes, kick the overnight 11-day relaunch with confidence.

**📌 Tier C — Deferred to post-relaunch (data-driven decisions):**
- TimeInBookDrift threshold tuning (`MIN_DRIFT=3.0` — see what the data produces across 11 days)
- Per-metric meaningfulness of VPIN / Kyle's λ on the IEX slice (decisions.md 2026-05-27-later #6 — assessed on cross-day stability)
- Slice qualifier rate on per-event narrations (26% today — debatable styling)
- Softer intent-shaped phrasing ("institutional reserve execution", "to drive price discovery") — borderline; don't over-restrict the prose

**🔁 Still open from earlier lists, post-relaunch:**
- [ ] **dev→prod export/import tooling** (task #91) — scripted `pg_dump`/`pg_restore` of *only* the narrative-layer tables. Idempotent, documented in `operations.md`. Not blocking the relaunch (the relaunch produces dev data; export is the step after).
- [ ] **Orphan prune after the relaunch** — `docs/sql/prune-stale-narrations.sql` collapses superseded rows. Run after the relaunch settles.

### Post-launch

> **Tiered baselines + rollup hierarchy — full design in [`tiered-baselines-design.md`](tiered-baselines-design.md).** Items 17–19 SHIPPED in Phase 2/3 (2026-05-26/27); the live post-launch work is the **calendar rollup hierarchy + cascade** (§8, decided 2026-05-27) and the drift-prevention items.

17. ✅ **Daily cagg retention/refresh window 30 d → 400 d** — done 2026-05-26 (`afd9b25`). Exact ~1-year baselines that outlive the 2-week wire retention; `RetentionSweepActivity` provably never drops it.
18. ✅ **`BaselineProvider`/`CaggBaselineProvider` + `VolumeDeviationScorer` refactor** — done 2026-05-26 (`e531d26`). Decoupled scoring from cagg SQL; behavior-preserving (42-symbol set reproduced exactly). + `RefreshBaselinesActivity` (first Score-phase step).
19. ✅ **Weekly rollup recompute-daily + prior-week window** — done 2026-05-26 (`b06da2b`). Now extends to the full hierarchy ↓.

**Live post-launch work (calendar rollup hierarchy + cascade — design §8, decided 2026-05-27):**

20. **Widen weekly prior-window 8 → 13** (one constant) so the weekly trend horizon = one quarter. **Moved to Tier A2 (2026-05-28 pre-relaunch)** — do tonight.
21. ✅ **`AggregateQuarterActivity`/`Workflow` + `quarterly_aggregate` table** — DONE 2026-05-28 (commit `d4e357c`). Mirror of `AggregateWeek` reading the quarter's ≤13 weekly rollups + prior 4 quarters; recompute-on-week-finalize; content-addressed; gated by `weekly_count >= 8` so dormant until ~Sept 30 first fire. Wired into `DailyPipelineWorkflow` after `AggregateWeekWorkflow`. Dormant-path validated in the 1-day test.
22. ✅ **`AggregateYearActivity`/`Workflow` + `yearly_aggregate` table** — DONE 2026-05-28 (commit `d4e357c`). The capstone "year in IEX microstructure" retrospective; reads 4 quarters; recompute-on-quarter-finalize; gated by `quarters >= 2`. Dormant until ~Q3 2027 first fire.
23. **`CascadeAggregate(fromDate)` driver** — the load-bearing new mechanism (design §8.2): after a historical backfill/re-synthesis, re-run every period from `fromDate` forward, bottom-up by tier (weeks → quarters → year), pruned by `content_hash`. Wired into the backfill path, NOT the nightly path. (~half day.) **Applies to weekly too** — a historical change does NOT auto-propagate downstream today.
24. ✅ **`TimeInBookDriftScorer`** — DONE 2026-05-27. Inter-day, mirrors `VolumeDeviationScorer`: today's per-symbol *average order-lifetime* vs its trailing median (collapse-or-stretch, symmetric drift magnitude). Reads a durable `daily_lifetime_by_symbol` table (PK (day,symbol), avg_lifetime_ns + order_count) the materialize step upserts after rebuilding `order_lifecycle`; `BaselineProvider` extended with `dayLifetimes`/`trailingLifetimeBaselines`/`trailingLifetimeWindows`; registered in `EventScorerRegistry`; RetentionSweep invariant updated to preserve the new baseline. avg-not-median (median needs `timescaledb_toolkit`, absent). Compiles clean; **runtime-validated by the overnight inter-day run** (needs ≥3 trailing days of lifetime baseline before it emits). **Monthly numeric tier** (cagg-on-cagg, §2.4) still deferred — only if multi-year *magnitude* reach is wanted.

**Analytics wave — richer microstructure/statistical computations for the breakdown (full menu in [`analytics-catalog.md`](analytics-catalog.md), 2026-05-27):**

> **PRE-LAUNCH ANALYTICS — STATUS (2026-05-27): BUILT + cheap-validated; overnight relaunch is the final validation.** Decisions in `decisions.md` 2026-05-27 "Analytics wave"; full menu/tiers/narration-set in `analytics-catalog.md`.
> - ✅ **`Analytics` pure-fn layer** (+ unit tests, all passing): slippage, one-sidedness, pct-of-baseline, robust-z/median/MAD/percentile, reversion, Fano burstiness, CV, effective-spread, order-to-trade, realized-vol, jump-ratio, HHI, normalized-entropy, lag-1 autocorr, CUSUM, Hawkes-style self-excitation, OFI, VPIN, Kyle's λ. Committed (`1e2d876`, `42a10bf`).
> - ✅ **Scorer-internal stats** across the registry: sweep `slippage_bps`, large_trade `pct_of_baseline_volume`, volume_deviation `robust_z`/`percentile_rank`/`volume_regime_shift`, post_cancel/layering `burstiness_fano`/`self_excitation`/`arrival_autocorr`, iceberg `display_ratio_pct`/`refill_cadence_cv`. Committed (`42a10bf`).
> - ✅ **`EnrichAnalyticsActivity`** (post-select, lazy for ~90–170 events; `jsonb ||` idempotent) — DB-dependent + window stats: liquidity_withdrawal two-sidedness; exec-window realized-vol/jump/VPIN/Kyle (sweep/large_trade/post_cancel/layering/halt); order-flow order-to-trade + pre-event OFI; book-depth imbalance. Wired into `ScoreWorkflow` after select. Committed (`188fb58`, `42a10bf`).
> - ✅ **Book-replay tier** (`BookSnapshotEngine`, single decode-only DPLS pass through the validated `OrderBookManager`, snapshot at event timestamps): sweep effective-spread, layering depth-from-touch, halt pre-event spread, liquidity_withdrawal %-of-book + recovery. BBO cross-checked vs TOPS on 05-08 (5/8 exact to the cent). Committed (`14678e7`, `42a10bf`).
> - ✅ **Day-level aggregates** (SynthesizeDay): `symbol_concentration_hhi` + `scorer_mix_entropy`. INTERPRET-tier `derived` reversion + pre→post VWAP move (`InterpretEventActivityImpl`, `interpret-v8`). [uncommitted until the narration test validates them]
> - ✅ **Narration-set** (`BlueprintExtractor` `extract-v4-full-analytics`) — per-scorer headline metric + "weave ≤1 supporting analytic" + slice-caveat note. **1-day before/after on 05-22 confirms it lands**: layering "order-to-trade ratio of infinite (0 fills)… depth from touch 299.3 bps… burstiness 9.43"; liquidity_withdrawal "two-sided… removed 29.5% of the book… recovered 87.0%"; iceberg "display ratio 0.52%"; large_trade "27.8% of baseline volume, VPIN 0.92" — all verifier-passed.
> - ✅ **`time_in_book_drift`** inter-day scorer + `daily_lifetime_by_symbol` baseline — built (item 24). Validated by the overnight inter-day run.
> - ⚖️ **`implied_reserve` (iceberg) dropped as ungroundable** — hidden size by design ⇒ any number is a fabrication; replaced with the grounded `display_ratio_pct` (displayed tip ÷ total). (The iceberg *scorer* itself is fully built.)
> - 🎯 **Overnight full 11-day relaunch = ONE batch** (`scripts/rerun-dataset.sh`), incremental via the `event_hash` skip. Validates INTERPRET/SYNTHESIZE/AGGREGATE + `time_in_book_drift` end-to-end AND produces the final launch dataset. Open judgment to assess on that run's data: **per-metric meaningfulness on the IEX slice** (esp. VPIN/Kyle's λ) — keep/cut decided with data, not a-priori.

26. **Shared `com.longexposure.analytics.*` layer + Tier-1 metrics** [pre-launch tranche ✅ above; remainder post-launch] — pure, unit-tested functions feeding the breakdown: order-to-trade ratio, OFI, sweep slippage + post-event reversion, time-in-book drift, robust z-score + percentile rank, realized vol, participation rate. The infra-heavy ones (OFI, reversion, depth-from-touch, implied reserve, side-split, halt pre-event signature) need **book-state replay / post-event windows / side-joins / tier baselines**. Computed **lazily for the ~90–170 selected events** (not all 660 K). Turns prose from "what happened" → "what it cost / what it implies." Catalog §2 + §4 (Tier 1).
27. **Decide the per-scorer "narration set"** — which curated subset of computed analytics enters the LLM-facing breakdown vs stays in `scored_events` for drill-down (catalog §3, option B + light headline/detail). This is a quality/cost choice, not correctness — needs a design pass. DESCRIBE gets the event's defining metrics; SYNTHESIZE/AGGREGATE get day/period-level ones (HHI/breadth/entropy), not per-event detail.
28. **Tier-2/3 analytics** (burstiness, periodicity, effective spread, depth imbalance, HHI, cancel-after-opposite-move, iceberg reserve; defer VPIN/Kyle's λ/Hawkes/jumps as slice-fragile) — catalog §4.

> Deploying the analytics wave is a deliberate **full re-run** (incremental via the `event_hash` skip) — the reason the 2-week scope is being held. Frontend drill-down ("every figure traces to IEX data") is the natural home for the *un*-narrated analytics.

**Drift-prevention (the systemic fix — see the 2026-05-27 doc audit):**

25. **De-number the docs + `scripts/docs-check.sh`** — hardcoded counts ("8 scorers", "13 workflows", "22 activities", "9 tables") rot on every code change and are error-prone to re-verify by hand (the 2026-05-27 audit caught a stale table-count AND a miscount *in the verification itself*). Replace counts with code pointers where possible; for the few we keep, a `docs-check.sh` greps the code (`EventScorerRegistry` size, `WorkerMain` registrations, `create_hypertable` calls) and diffs against the docs so a stale number fails loudly. Aligns with the global CLAUDE.md anti-pattern "static counts in docs."

- **Extend wire retention to 4 weeks** if deeper *re-scoreability* wanted (one-line `RETENTION_WEEKS`; independent of the rollup horizon — see design §8.4) — and only *then* revisit the cross-node stage-pipelined backfill (deferred 2026-05-25).
- **SUMMARIZE stage** (background baselines + day-aggregates as a named pipeline stage).

### Prompt-level limitations (queued for v5)

Captured 2026-05-20 from the v4 re-narration run. Empirically ~0.6 % filler rate but the residual cases are predictable:

- **Residual current-state filler** — "the security is now trading normally" / "regular market activity resumed". The breakdown describes events that *occurred*; current state isn't in it. Prompt fix: explicit rule "describe only what happened during the event window — do not assert current or post-event state."
- **Uneven co_occurring narration** — IWM/INTC liquidity_withdrawals integrate enrichment beautifully ("Co-occurring activity included 565,131 shares in layering orders and $199,041.20 in sweep notional volume"). Iceberg events with 285+ child orders just skip the enrichment. Prompt fix: "if the blueprint contains `co_occurring` values, your narration must reference at least one of them."
- **ETN-style ticker/word collision** — when a ticker spells a real English noun (ETN, ET, GE, FOR, AT) and the `symbols` table lacks `company_name` for it, the model treats the ticker as the common noun. Real fix is enrichment-side (fill missing rows). Prompt band-aid: "the `symbol` field is ALWAYS a ticker, even if it spells a real English word."
- **Higher run-to-run variance** under Qwen RENDER (temp=0.7) — same event re-narrated produces different sentence orderings. Verifier still passes both. Acceptable tradeoff for prose variety; not a v5 item.

### Next session (after the above settles)

4. **Score weight audit** — sort top-20 events per scorer per day, eyeball whether the multipliers (`log10(shares) × count`, etc.) actually surface "trader-flag-worthy" events. Adjust by feel; document why.
5. **Per-symbol liquidity-tier weighting** — multiply scores by a tier factor so a halt on AAPL ranks higher than a halt on a micro-cap. Requires symbol enrichment (already shipped) + a tier classification (mega-cap / large / mid / small / micro from `prev_close × shares_outstanding`).
6. **Layer 0 expansion** (per-event interpretation pass) — per-event interpretive second LLM pass that says what a pattern likely *means*, grounded in a pattern catalog + surrounding wire context. Different concern from Layer 3 — Layer 0 enriches individual narrations with interpretation; Layer 3 ties the day's narrations into a single thematic paragraph.

### Post-backfill polish (from 2026-05-22 audit)

Surfaced auditing the full 5-day backfill (05-18→05-22). One actionable item (number formatting), one verifier bug fix, and a corrected understanding of the residual failures.

**CORRECTION (logged 2026-05-24, supersedes earlier entries in this section):** earlier notes here claimed the DESCRIBE/INTERPRET failures came from *missing* pre-computed fields (add `orders_per_second` to layering / `total_shares` to iceberg). **That was wrong — those fields already exist.** `IcebergScorer` emits `total_shares` (line 191); `LayeringScorer` emits `total_shares`, `orders_per_second` (guarded only by `durationSec > 0`), `orders_per_level`, `notional_per_level` (the 2026-05-22 DETECT enrichment). The residual ~1–2%/stage failures happen *despite* the grounded value being present, because the model derives a number anyway: (a) **recomputes from a rounded display value** — RKLB layering has grounded `orders_per_second≈351.4` but the model re-divided `131 ÷ "372.7 ms"` and wrote `351.5`, off by one rounding step; (b) **magnitude approximation** — OXY iceberg states exact `$12,256.06` then adds "over `$12,000`"; (c) **cross-window summation** — JHX iceberg interp sums pre-window 1,946 + post-window 1,775 → `3,721` (neither a field); (d) **meta-commentary leak** — JHX 05-20 leaked "(Note: Blueprint lists 68415.86…). Correction:…" into prose (exactly **1** occurrence in 819 narratives). There is **no clean enrichment fix** — the data is already there; the model chooses to derive. These are inherent to LLM rendering, the verifier is the designed safety net (all caught, all `verifier_passed=false` and filterable), and the rate is low + stable. Not worth a prompt band-aid. The one prose-quality watch item is the meta-commentary leak — if it recurs at >~1/1000, tighten the RENDER prompt against "Note:/Correction:" artifacts; at 1/819 it's a one-off, leave it.

- [x] **Number-formatting pass in `BreakdownFmt`** — done 2026-05-26 (`76c49d1`). `BreakdownFmt.formatDollars(v)` → `$X,XXX.XX`; routed the 6 notional fields (notional_dollars / notional_per_fill / notional_per_level) through it. Counts/shares were already comma-formatted via `formatCount`. Prices left numeric (sub-penny precision). The halt `55.8 percentage of regular session` phrasing is **still open** — a separate, smaller field (`duration_pct_of_regular_session`); pre-format as `55.8%` or add a `_label`. Low priority.
- [x] **`SynthesisVerifier` dotted-ticker tokenization** — done (`b64d26f`); `TICKER_RE` now captures `BRK.B` whole, `extractTickers()` shared with the AGGREGATE verifier. Confirmed in place during the 2026-05-26 audit.
- [x] **Verifier-driven retry (all 4 LLM stages)** — done 2026-05-26 (`6c1030f`). DESCRIBE/INTERPRET/SYNTHESIZE/AGGREGATE re-roll a rejected LLM call up to 3× (temp 0.7–1.0 variance), keeping the first passing result. Hides transient number-glitch failures. Proven: 05-19 interpret 162/164 → 164/164 on re-run.
- [x] **co_occurring breakdown fixes** — done 2026-05-26 (`27b3639`). `sum_deletes` now integer (`"57"` not `57.0` — added "deletes" to `isIntegerCountField`); dropped `total_children` (was narrated as "3 total children events", leaking the data-model vocabulary). Verified clean on the 05-08 re-run (0 "children", 0 raw-dollar on live rows).

### Open residuals (from the 2026-05-26 audit — not blocking launch)

- [ ] **Pattern-name mislabel in INTERPRET (verifier-uncatchable).** Observed: an AKBA *layering* event's interpretation ended "…leaving the *liquidity withdrawal* in isolation" — the model named the wrong pattern. The verifier checks numbers + ticker presence, NOT pattern-name correctness, so it passes. Rare. Fix would be a verifier enhancement: flag prose that names a scorer pattern ≠ the event's own scorer_id (cross-check against a known pattern-name vocabulary). Post-launch.
- [x] **✅ AGGREGATE streak-length fabrication — FIXED 2026-05-27** (was 🚩 LAUNCH-RELEVANT). *(Was: the 05-18 weekly rollup, with exactly 1 prior week, claimed "a fifth consecutive week" / "the third straight week where TQQQ…" — fabricated streak length. `SynthesisVerifier` passed it because the streak is a written ordinal + qualitative claim, not a digit it grounds.)* Both fixes shipped in `AggregateWeekActivityImpl` (`PROMPT_VERSION="aggregate-v5-streak-bound-2026-05-27"`): (1) **prompt** now states the actual prior-week *count* and bounds streak language to it ("you have N prior weeks; longest honest streak is N+1 weeks"), and the old "third straight week" examples were removed from the prompt — they were *seeding* the fabrication; (2) **verifier net** — `maxStreakWeeksClaimed(text)` regex (digit + word ordinals via `STREAK_RE`/`ORD_WORDS`) computes the largest "Nth consecutive/straight week" claimed; the retry loop accepts only when `claimedStreak <= priorWeeks + 1`, else re-rolls and finally stores `verifier_passed=false` + a mismatch note. Validated post-fix (1-prior week now renders "a second consecutive period"). The bumped `PROMPT_VERSION` invalidates the content-hash, so tonight's relaunch re-aggregates every week and overwrites the 05-18 known-bad row.
- [ ] **Duration pluralization nit.** IWM liquidity_withdrawal rendered "1 seconds" (should be "1 second"). Cosmetic; the duration value is grounded, just not singularized. Could pre-format a `duration_humanized` that handles the 1-unit case, or leave it. Very low priority.
- [ ] **Run the orphan prune after the full re-run.** `docs/sql/prune-stale-narrations.sql` — dry-run validated (keep 1817 narr / 1808 interp; remove ~1691 narr + ~328 interp orphans, including 05-08's 1723-row bloat). Run the DELETE block once the re-run finishes so the latest formatted/retried rows are the winners.
- [ ] **Compound phase-label stitch in DESCRIBE prose (verifier-clean, grammatical glue).** Found 2026-05-28 in the week-of-05-11 ground-check (2/9 events affected, all verifier-passed — every number traces):
   - **GMRS halt (05-13)**: *"The halt began in pre-market to midday."* — model glued `halt_start_phase_label="in pre-market trading"` + `halt_end_phase_label="around midday"` without a connector verb. Should be *"spanning pre-market to midday"* or *"began in pre-market and resumed around midday"*.
   - **DRAM sweep (05-14)**: *"Slippage amounted to walked 7.4 bps up."* — fused two prompt templates ("Slippage amounted to X" + "walked X bps up"). Should be *"Slippage walked 7.4 bps up"* or *"Slippage amounted to 7.4 bps."*
   Pure prose-quality (the data layer is fine; the model is constructing an ungrammatical bridge between two grounded facts). *Fix*: one RENDER-prompt iteration that explicitly forbids compound-stitched phase-label / slippage-direction clauses with positive examples for both shapes, plus a 1-day re-narration to confirm. Post-launch — ~2/164 rate is low enough to defer.
- [ ] **`time_in_book_drift` thin-baseline data-quality flag.** Observed 2026-05-28 on 05-15: OVT scored `drift_x=5748.0` with `baseline_window_trading_days=3` — the narration is faithfully grounded (today's avg lifetime 16.5 sec vs baseline median 2.9 ms is arithmetically correct), but a 3-day baseline on a thin bond ETF is statistically too noisy to support a 5,748× signal. The scorer currently gates on `baseline_window >= 3` days; values get progressively wild for thin names at the low end of that window. *Options*: (a) bump `MIN_BASELINE_DAYS` from 3 → 5 in `TimeInBookDriftScorer` so we have more median-stability headroom; (b) keep gate at 3 but add a `baseline_confidence` field that the prose can hedge against ("a 3-day baseline supports the observation but the multiple is sensitive to sample size"); (c) leave it — when the 30-day backfill happens, all baselines firm up automatically. *Decide post-relaunch*: look at the full 11-day dataset and see how many `time_in_book_drift` events have `baseline_window_trading_days < 5`. If it's a long tail, do (a); if isolated, do (c). Distinct from the existing `MIN_DRIFT` tuning in Tier C.
- [ ] **Inter-day scorers have no INTERPRET prose (structural gap).** Found 2026-05-28 in week-of-05-11 audit: `volume_deviation` (TDIC) and `time_in_book_drift` (OVT) events have NO row in `interpretations` — DESCRIBE only. INTERPRET reads `TradeWindow.query()` for ±60 sec around the event timestamp; that framing is intraday-shaped and doesn't have a natural mapping for "today's whole-day metric vs trailing-window baseline." Implications: (1) the per-event UI drill-down for inter-day events will show only DESCRIBE prose, no second paragraph. (2) SYNTHESIZE falls back to DESCRIBE for these events (per architecture docs § "SYNTHESIZE per-event input contract"), so they DO contribute to daily themes, just via the simpler narration. *Decide post-relaunch*: (a) confirm the skip is intentional in `InterpretEventActivityImpl` (vs an oversight); (b) if it's worth adding, design a different INTERPRET shape for inter-day events — read today's full intraday volume / order-lifetime profile rather than ±60 sec around an event ts that's effectively the whole day. The catalog already has interpretive material for volume surges and lifetime regime shifts. Low priority — DESCRIBE-only is workable for the inter-day events for v1.
- [ ] **Borderline interpretive phrasing in INTERPRET (catalog-driver tier).** Observed 2026-05-28: PEG iceberg INTERPRET ended *"consistent with institutional execution seeking to minimize market impact"* — defensible because (i) "consistent with" qualifies vs asserts, and (ii) iceberg's pattern-catalog entry lists "institutional execution + market-impact minimization" as a documented legitimate driver. But it's an intent claim phrased softly. The intent denylist (Tier A1: `manipul/spoof*/front-?run/wash/gam(e|ing|ed)/illegal/fake`) wouldn't catch it; the wording is on the right side of the policy. *Track*: count occurrences across the full re-run; if "consistent with institutional execution" / "intended to minimize" / similar soft-intent constructions exceed a threshold (say ≥ 5% of icebergs / large-trades), tighten the prompt to require purely-observed phrasing ("the iceberg moved 32× the pre-window volume with a 2.4 bps VWAP shift"). Otherwise leave as catalog-driver vocabulary doing its intended job.
- [ ] **"Regulatory suspension" phrasing on halts with `halt_reason="NA"`.** Observed 2026-05-28: SSCP halt (05-12) INTERPRET said *"this regulatory suspension on SSCP appears in isolation from wire activity"* — but the breakdown's `halt_reason: "NA"` means we don't actually know the halt was regulatory. The verifier doesn't catch it (no number to fail; not in intent denylist). *Fix*: when `halt_reason="NA"` / missing / `null`, the INTERPRET prompt must say "trading halt" or "trading suspension" — never "regulatory" / "circuit-breaker" / "news halt" / any reason-specific qualifier. Add to the INTERPRET prompt's halt section. Verifier could optionally enforce: if prose contains ["regulatory", "circuit-breaker", "T1", "LULD", "MCB", "news"] AND breakdown.halt_reason is NA/null, reject. Low priority — single-digit halts have NA reasons; full re-run will surface frequency.
- [x] **✅ Cardinal word-form numerals bypassed verifier — FIXED 2026-05-28** (commit pending). Observed 2026-05-28 in 05-12 daily synthesis: 5/5 word-form counts were wrong (eight/ten/six/four/three vs actual 14/12/14/5/4) and bypassed verification entirely because `GroundingVerifier.NUMBER_RE` is digit-only. Generalized across days: every daily synthesis had 2-6 unchecked word-form numerals. **Fix**: added `GroundingVerifier.cardinalWordNumbersIn()` extracting "zero".."thousand" + composite "{unit} hundred|thousand" patterns, wired into all three verifiers (DESCRIBE / INTERPRET / SYNTHESIZE → AggregateWeek/Quarter/Year), bumped SYNTHESIZE/AGGREGATE PROMPT_VERSIONs to v6 to force re-run. Tested on 05-12: new prose shifted from "eight TQQQ events" (wrong) to "TQQQ with 18 incidents" (digit, grounded). Re-running across 05-11..05-18; will re-build week-of-05-11 + week-of-05-18 aggregates after.
- [x] **✅ Intent-denylist false-positive on "gaming" — DROPPED 2026-05-28** (same commit). `gam(?:ing|ed)` regex flagged "gaming stocks like DKNG" (sector use — DraftKings) as intent-claim and burned retry-3× without resolving. Dropped from both `SynthesisVerifier` and `InterpretationVerifier` denylists. Kept: manipul / spoof / front-run / wash-trad / illegal / fake — all unambiguous.
- [x] **✅ Number misattribution in SYNTHESIZE — FIXED 2026-05-28 via structural AttributionVerifier** (commit `bfa3da3`). The bug: prose said "TQQQ which recorded ten distinct order-deletion events" when TQQQ actually had 20 liquidity_withdrawals; "10" passed the existing verifier because it was in the haystack (as the day's halt count), but the (TQQQ, 10, liquidity_withdrawal) triple did not match data. **Fix**: new `AttributionVerifier` class extracts (subject, count, scorer-type) triples from prose via a longest-first noun-phrase regex + bounded-window subject/count lookup; each triple checked against the activity-supplied by_symbol_by_scorer truth map. Three claim shapes covered (subject-led / verb-led / possessive); digit + word-form numerals via the existing `GroundingVerifier.cardinalWordNumbersIn`. Maps populated in `SynthesizeDayActivityImpl.computeDayAggregates` via out-params and passed to the new 5-arg `SynthesisVerifier.verify()` overload — NOT exposed in the LLM-facing JSON (band-aid avoidance per project structural-fix discipline). PROMPT_VERSION bumped synthesize-v6 → v7. 15 unit tests. Reverted earlier same-day attempts (the prompt-instruction + JSON-only band-aids) recorded for posterity in `SynthesizeDayActivityImpl` javadoc.

   *Out of scope, separate todo*: multi-symbol attribution ("DGP, TQQQ, and QQQ collectively generated 49"), AggregateWeek/Quarter/Year attribution (would need period-aggregated truth maps — same pattern, deferred).
- [ ] **Derived-sum verification gap (verifier-uncatchable, prompt-tractable).** Observed 2026-05-28 in v6 05-11 synthesis: "DGP, TQQQ, and QQQ collectively generated 49 of the session's 168 recorded events" — the sum (14+21+14=49) is mathematically correct but "49" not in haystack as a single token. The model is doing valid journalistic arithmetic; the verifier can only check raw presence. *Fix path*: add common derived totals to the synthesis haystack (sum of top 3 by_symbol counts, sum of by_scorer counts, etc.) — a few lines in `SynthesizeDayActivityImpl`. Or constrain the prompt to cite raw counts only. Low priority — model gets it right when it derives but verifier rejects; user-facing impact is only the verifier_passed=false → hidden by API.
- [ ] **🚩 DESCRIBE/selected_events FK orphaning — DATA-INTEGRITY ISSUE.** Found 2026-05-28 during 05-12 INTERPRET audit, but spans the dataset:

   | trading_date | selected | DESCRIBE-linked | missing | orphan narratives (different selected_id) |
   |---|---:|---:|---:|---:|
   | 2026-05-11 | 168 | **0** | 168 | 481 |
   | 2026-05-12 | 163 | 106 | 57 | 367 |
   | 2026-05-13 | 170 | 170 | 0 | 322 |
   | 2026-05-14 | 180 | 180 | 0 | 311 |
   | 2026-05-15 | 188 | 188 | 0 | 310 |
   | 2026-05-18 | 196 | 196 | 0 | 315 |
   | 2026-05-19 | 193 | 46 (in-flight) | — | 362 |

   **Root cause**: re-scoring a day generates new `selected_id` (BIGSERIAL) values. The `narratives` table content-addresses by `event_hash` and stays after re-score, but its `selected_id` FK points at the OLD selected_events row that's been DELETE+INSERT'd. When `NarrateEventActivity` then runs and the event_hash matches, the activity SKIPS the LLM call (correct on content) but doesn't UPDATE the narrative's `selected_id` to the new one. Result: prose exists but the API query (joining on selected_id) returns nothing for these events.

   **Visibility impact**: The 168 events on 05-11 + 57 on 05-12 will appear in the frontend's drill-down UI with INTERPRET prose but NO DESCRIBE prose. SYNTHESIZE and AGGREGATE are unaffected — they read INTERPRET, which is also content-addressed but somehow stays linked (worth confirming whether its FK works the same way).

   **Three fix options** (post-relaunch, NOT blocking v2):
   1. **SQL re-link** — UPDATE narratives.selected_id = current selected_events.selected_id WHERE join on (trading_date, symbol, event_ts, event_type, score). Cheap, idempotent, no LLM cost. Restores existing prose.
   2. **Force re-narrate** for the affected days — invoke `NarrateWorkflow` with a cache-bust env var. Expensive (~30-50 min wall per day) but produces fresh rows with current selected_ids.
   3. **Fix `NarrateEventActivity`** to UPDATE existing rows' `selected_id` on cache hit. The proper code fix going forward. Combine with option 1 to repair the existing dataset.

   **Decision recommended**: do (1) + (3) post-relaunch. (1) repairs the existing data in seconds; (3) prevents recurrence. Investigation needed first to confirm `InterpretEventActivity` doesn't have the same bug (its FK is also `selected_id`, but coverage is 100% so either it doesn't have the bug OR it's been re-linked through some other path).

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
- [x] **LLM-skip cache (memoization phase 1) — DONE 2026-05-26** (committed `4247805`). DESCRIBE + INTERPRET now check for a `verifier_passed` row with this `event_hash`/`interpretation_hash` before the LLM call: reuse passes, retry failures. Proven: re-narrating 05-13 → 168 skips / 2 LLM calls / ~40 s (vs ~50 min). Next memoization phases (full `MemoizedStage` abstraction, content-address SYNTHESIZE/AGGREGATE, prompt-TEXT hashing) per `cost-model-and-wiring.md` §6.7; backend-completion sequence in `launch-sprint.md`. **Next phase = the weekly rollup machinery (recompute-daily + prior-week window + pipeline wiring + content-address AGGREGATE).** Original finding for reference: `NarrateEventActivity` + `InterpretEventActivity` previously **always** called the LLM for every selected event — `event_hash`/`interpretation_hash` are upsert keys only, never consulted to skip. So re-scoring/backfilling a day re-narrates the whole day (~90 min LLM/day), not the changed events. **Fix:** a `verifier_passed`-row-with-this-`event_hash`-exists skip before the LLM call (or filter the fan-out list). Safe because `event_hash` is provably stable for unchanged breakdowns (05-22 re-score: 160→163 rows, only 3 genuinely new — would've been ~3 LLM calls with the skip, not ~160). **Do this BEFORE any large re-score/backfill** — it turns "add a scorer to N days of history" from N×90 min into minutes. Caveat: only helps when breakdowns are byte-stable across the re-run. **Full design (content-addressed memoization across all tiers, the prompt-TEXT-hashing foolproofing, the auto-invalidation cascade, build scope) is `cost-model-and-wiring.md` Part 6.** Minimal launch-relevant piece = the per-event DESCRIBE+INTERPRET exists-skip (§6.5).
- [ ] **README cache claim is wrong** — "caching by event hash … re-running identical events is a cache hit" describes storage idempotency, not compute-skip (there is no skip; see above). One-line correction to README's caching section post-launch.

### Cross-event combining — built, tested, RETIRED 2026-05-20

- `CombineRelatedEventsActivity` was built, tested on 2026-05-08, and disabled because the interval-overlap rule absorbed ms-scale events into sec-scale events (28-36 constituents per combined event, mostly nested-mechanism cases like a liquidity_withdrawal absorbing post_cancel/layering events happening inside it). The combined narrations were unreadable and the verifier couldn't validate dotted source-field paths into nested breakdowns.
- The replacement architecture (co-occurrence enrichment + Layer 3 synthesis) is the new direction. See @decisions.md 2026-05-20 for the full rationale.
- The combine code stays in the repo until Phase B of the new plan deletes it. `scored_events.subsumed_by_event_id` column stays — it gets reused by enrichment.

### Operational

- **Materialize step is the new bottleneck.** ~20 min today on a 162 M-row INSERT. If it fails midway, pre-clean handles idempotency cleanly. But if mem_limit pressure ever causes the postgres container to get OOM-killed during the INSERT, the activity will retry — and if it keeps failing, the workflow stalls there with no scoring/narration. Worth a monitoring alert.
- **Postgres `mem_limit: 48g` is generous.** With legal-tender's ArangoDB at 24 GB + monitor stack + dev tools, host headroom is ~24 GB during scoring. If anything else runs hot at the same time we could OOM-kill something. The cron is at midnight ET specifically to avoid concurrency with other workloads, but ad-hoc runs need to be scheduled mindfully.
- **`order_lifecycle` table is now load-bearing.** If a schema change to `orders_add` / `orders_delete` / `orders_executed` lands without re-running `MaterializeWorkflow`, the lifecycle table goes stale. We have no test for this; need to remember.
- **Symbol metadata refresh runs weekly.** If a new IPO lists between refreshes, its narrations won't have company_name / sector. Acceptable for now; document as known limitation.
- **`isAlreadyIngested` marks a day "done" after parse+validate, before the LLM stages** (the `pipeline_runs.status='ok'` row is written by `completeRun` mid-`DailyPipelineWorkflow`, before Score→Narrate→Interpret→Synthesize). So a day **terminated mid-LLM** is left with `status='ok'` but no narratives/synthesis — and a backfill re-run will then **`skipped_already_ingested`** it, leaving it half-processed. Hit this 2026-05-25 pausing the prior-week backfill to run AGGREGATE: 05-11 had been terminated mid-scoring, kept its `'ok'`, and the relaunched driver skipped it. **Workaround: `forceReingest=true`** bypasses the check (the backfill driver now uses it). *Proper fix (queued):* either write `status='ok'` only at full pipeline completion, or have `isAlreadyIngested` check for actual narrative/synthesis presence rather than the `'ok'` flag. Pre-existing; not launch-blocking.
- **`SchemaManager.apply()` is not concurrency-safe on first-time table creation.** `CREATE TABLE IF NOT EXISTS` races on the Postgres `pg_type` catalog unique index when two activities apply the schema simultaneously and the table is brand-new — fails with `duplicate key value violates unique constraint "pg_type_typname_nsp_index"`. Hit this 2026-05-25 the first time `AggregateWeekActivity` ran (the activity retried, and the two attempts raced to create the new `weekly_aggregate` table); once the table existed, re-runs were clean (all `IF NOT EXISTS` no-ops). Only bites on the first creation of a NEW table under concurrent `apply()`; harmless once the schema is stable. *Proper fix (queued, low priority):* catch the duplicate-`pg_type` SQLState in `splitStatements` execution and treat as already-exists, or serialize first-time DDL via an advisory lock. Pre-existing latent race exposed by adding a new table.
- **Re-scoring an already-narrated day forces a FULL re-narration (cache miss for the whole day).** Found 2026-05-25 testing `VolumeDeviationScorer`: ran `ScoreWorkflow(05-22)` to add the new scorer's events, then `NarrateWorkflow(05-22)` expecting ~2 fresh narrations (the new vol-dev events) + ~160 cache hits. Instead all ~162 re-narrated (~56 min, full LLM cost). Cause: `ScoreWorkflow` regenerates every scorer's breakdown from scratch, and `event_hash = SHA256(scorer_id + breakdown + prompt-versions)` — any non-identical breakdown byte (field order, an enrichment value, a co_occurring recount) changes the hash → narration cache misses. So "re-score a day to add one scorer" is not the cheap incremental it looks like; it pays for the whole day again. *Implications:* (a) the "add a scorer + re-score historical days" backfill path is expensive — budget full re-narration per day; (b) if we want cheap incremental, the narration cache would need to key on a per-event content hash that's stable across unrelated scorers' re-runs (e.g., hash only that event's own breakdown, which it already does — so the miss means the event's own breakdown changed; worth confirming whether the 7 intraday breakdowns are truly byte-identical across re-scores or drift via co_occurrence/enrichment). Not launch-blocking; relevant whenever we re-score.
- **Inter-day (`volume_deviation`) narration prose is grounded but mechanical.** The DESCRIBE prompt is tuned for intraday event shapes; on the new event type it produced verifier-clean but stiff prose ("saw its trading volume deviate by a multiple of 22.2 on the IEX" rather than "traded 22.2× its 2-week median"). Validated 2026-05-25: MEHA 22.2× and IMRX 14.5× both passed the verifier with every figure grounded — the contract works, the register just isn't tuned. *Refinement (queued):* a volume_deviation-aware touch in the DESCRIBE prompt (or a pattern-catalog entry) for more natural inter-day phrasing. Not blocking — first inter-day narration, grounding solid.

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

- [x] **Delete the retired combine code** (done 2026-05-20) — `CombineRelatedEventsActivity[Impl]`, `CombineWorkflow[Impl]` files removed. WorkerMain registrations cleaned. `subsumed_by_event_id` column stays (used by enrichment).

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

- [ ] **Day-level / cross-node stage-pipelined backfill.** **DEFERRED 2026-05-25 — see `decisions.md` "Cross-node stage-pipelined backfill: considered, deferred".** The idea: overlap the two disjoint resource pools — run day N+1's luv-side stages (download/parse/score/materialize/compress) while day N's LLM stages run on joi — so the backfill isn't gated by the sum of both. Real but bounded: the LLM stages of different days can never overlap (joi's 2-slot cap + one-LLM-workflow rule), so total LLM time (~50 min/day) is an irreducible floor; pipelining only hides the luv-side time behind it (~12 h → ~5-6 h for a 5-day run). **Only ever useful for backfill, never the nightly cron.** Now that retention is capped at 2 weeks the absolute saving is small and one-time, so it doesn't justify the orchestration complexity (cross-day LLM-phase mutex + mid-pipeline failure handling) this close to launch. Revisit only if retention extends to 4+ weeks or we start frequent re-backfills. Implementation if revived: parent workflow firing `DailyPipelineWorkflow` children with a configurable concurrency window + a shared LLM-phase lock. Effort: 1-2 days.

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

---

## Deferred renames (queued, intentional)

Tracked here so they don't get forgotten. All flagged on 2026-05-22 as part of the naming-cleanup pass; deferred because the surface area is large enough to be its own focused work, not a launch-sprint distraction.

- [ ] **`GroundingVerifier` decomposition.** The class is named singular but runs 4 distinct layered grounding checks (source_field resolution, prose-numbers-in-haystack, symbol presence, company-name agreement). Either rename to `LayeredGroundingVerifier` to be honest about its shape, or decompose into 4 separate check classes plus a coordinator. Touches: `GroundingVerifier.java`, `ProseRenderer.java`, `NarrateEventsActivityImpl.java`, the test file. ~5–10 files. Effort: ~3 hours. Defer to post-launch.

- [ ] **`breakdown` JSONB column rename.** The column carries event measurements + symbol metadata + co_occurring nested events; the name "breakdown" is more about score transparency than what's actually inside. `measurements` or `event_facts` would be more honest, but renaming a JSONB column touches: schema migration, all 7 scorers, `ScoredEvent` / `ScoringContext` records, every activity that reads it (`Enrich…ActivityImpl`, `BlueprintExtractor`, `ProseRenderer`, `NarrateEventsActivityImpl`, `SelectTopEventsActivityImpl`), SQL queries across the API layer in **a different repo** (`vedanta-systems`/`src/server/routes/long-exposure.ts`), Express response types, and frontend `long-exposure-browser.tsx`. Cross-repo + schema migration + downstream consumers. Effort: ~half a day. Defer to post-launch.

- [ ] **Concept-primer rewrite using function names.** `docs/concepts.md` uses "the 4 layers of data" as its pedagogical structure. A clean rewrite would use WIRE / DETECT / DESCRIBE / INTERPRET / SYNTHESIZE / AGGREGATE as the organizing principle. Effort: ~2 hours, including making sure the new framing reads as a primer (not just a substitution). Defer to post-launch.

- [ ] **Bulk Layer-N → function-name rename across older docs.** `docs/scoring-and-narration.md`, `docs/todo.md` (this file), `docs/decisions.md`, `docs/launch-sprint.md`, `parser/src/main/resources/pattern-catalog.md` all still use Layer-N vocabulary in places. A 2026-05-22 bulk-rename attempt produced too many false matches (e.g., "Layer-4 verifier" referring to grounding-verifier check tier 4, not pipeline stage 4) — needs file-by-file careful editing rather than `sed`. Each doc currently has a "Naming note" header pointing at `pipeline-architecture.md` for the canonical vocabulary. Effort: ~3 hours across all docs. Defer to post-launch.
