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

### 🚩 Round 2 — pre-overnight fixes (kicked off ~midnight EDT 2026-05-29)

After the first overnight relaunch ran 2026-05-28 morning + the all-day audit surfaced new findings, the dataset is being thrown out and a SECOND overnight rerun is queued with these additional fixes. All items below shipped between 18:00 EDT and 19:30 EDT 2026-05-28 with a 1-day end-to-end test on 05-13 before the full kick.

- [x] **✅ R1. AttributionVerifier "split between A and B" false-positive — FIXED** (commit `aeba0e4`). Track consumed count positions in `extractClaims`; each count attributes to ONLY the closest noun via per-count "used" set. Regression test for "X events split between A and B." Verified on 05-13 — prose with the pattern correctly produces 1 claim, not 3.

- [x] **✅ R2. Per-symbol cap on selection — SHIPPED** (commit `0511866`). `PER_SYMBOL_CAP = 8` applied AFTER the per-scorer budget pass. Verified on 05-13: TQQQ dropped from 20 → 8 selected, total 170 → 150 events. Heavy symbols rebalanced, synthesis prose breadth improves.

- [x] **✅ R3. Per-scorer floor 1 → 3 — SHIPPED** (commit `0511866`). Same file as R2. Rare-but-interesting patterns guaranteed at least 3 events. Verified on 05-13: volume_deviation = 3 events.

- [x] **✅ R4. Halt scoring linear → log10 — SHIPPED** (commit `0511866`). `score = log10(duration_seconds + 1.0)` (+1 guards log10(0)). Verified on 05-13: halt scores compressed from linear [105–23400] range to log [3.51–4.36] range — keeps ordinal order, less marathon-bias.

- [x] **✅ R5. AttributionVerifier extended to AggregateWeek — SHIPPED** (commit `3109133`). `loadWeekTruthMaps` queries `selected_events` over the week's date range to build by_symbol_by_scorer + by_symbol_total sums. Passed to the new 5-arg verify overload. PROMPT_VERSION bumped aggregate-v6 → v7.

- [x] **✅ R6. 1-day end-to-end test on 05-13 — PASSED 2026-05-28 18:17 EDT.** Score (150 events, R2/R3/R4 visible) → Narrate (150/150 cache-hit + relinked) → Interpret (146/150, 4 inter-day skips expected) → Synth (v7 verifier_passed=true with attribution check active). FK-orphaning bug discovered + fixed mid-test.

- [x] **✅ R7. Overnight script `rerun-dataset-v3.sh`** — committed `9a93b0b`. 12-day plan (week-of-05-11 + week-of-05-18 + new 05-26/27) with interleaved AggregateWeek-05-11/05-18/05-25. Pcaps pre-fetched via `prefetch2-download-*` DownloadWorkflows.

- [x] **✅ FK relink on cache hit — STRUCTURAL FIX** (commit `d13fc52`, HARD BLOCKER discovered during R6 test). `NarrateEventActivity` + `InterpretEventActivity` now UPDATE the cached row's `selected_id` to point at the current selected_events row instead of leaving stale orphans. Without this, re-scoring would produce 0 user-visible narrations. The cache-skip still saves the LLM call.

- [x] **✅ Hyphenated noun match** (commit `d9841fb`). `AttributionVerifier` builds NOUN_RE with `[\s\-]+` between words so "depth-contraction" matches the same vocabulary key as "depth contraction." 1 new test.

### 🚩 Round 3 — pre-overnight structural improvements (2026-05-28 evening, continuation)

After Round 2 (R1-R7) shipped + the 05-13 1-day test passed, continuing structural improvements before the overnight kickoff. All items below are "no band-aid" fixes: data-layer or shared-utility extractions, never prompt-rule patches. Implementation order picked for low risk + high leverage; 1-day test gate before the overnight.

**🚨 Tomorrow's checkpoint — read this first if you come back to a half-finished state:**

The dataset state on disk: **partial overnight run completed days 05-12 through 05-20 (7 days), stalled at day 11/12 with 05-21 narrate-only-partial (68/~200 narratives)**, and 05-22 unreached. Driver script died 2026-05-28 16:39 EDT; `rerun2-NarrateWorkflow-20260521-163954` was Terminated 4 hr later (not the expected 05:28 cleanup). See "Overnight failure diagnosis" section below.

Cause: still under investigation. Hypothesis: the rerun-dataset-v2.sh shell driver lost its parent shell when the terminal closed and `nohup`/`disown` didn't keep it alive, OR the SDK CLI inside it exited on a transient network blip without retrying. The Temporal workflow Terminated state is the *symptom* — the driver script was no longer there to send heartbeats / wait on it.

Where we are now (2026-05-28 ~20:40 EDT):
  - Phase 6 (1-day test on 05-12): ✅ PASSED before the overnight kick
  - All Round 3 phases 1-5 + 7c/7e/7f shipped
  - Phase 8 (PipelineWorkflow) ✅ shipped tonight (commit `1352dcb`) — replaces all `scripts/rerun-dataset-*.sh` scripts
  - Phase 7/7b/9-A/9b still pending
  - Overnight stall left dataset partial — needs re-kick from 05-21 forward (05-12→05-20 are clean and re-usable; their content_hash will skip on re-run)

**Score-formula audit (Phase 9c) findings 2026-05-28 19:00 EDT:**
  - All scorers except **sweep** look sensible (top events match "obviously top" intuition).
  - **sweep formula `log10($notional) × distinct_levels` is over-indexed on level count**: 05-12 audit shows a $42K LDOS sweep across 13 levels scoring 60.1, beating a $458K CSX sweep across 9 levels scoring 50.95. Level multiplier dominates notional.
  - Cross-scorer normalization (F) PUNTED — score ranges differ by 4 orders of magnitude across scorers but within-scorer percentile rank sidesteps that. No clear cross-scorer miss-cases. Revisit post-launch if a real journalistic gap emerges.
  - **Added Phase 7f: SweepScorer formula** — change to `log10($notional) + log10(distinct_levels)` (additive in log space) so both contribute without dominating. ~10 min.

- [x] **✅ Phase 1. AggregateQuarter + AggregateYear attribution** (commit `adafcb8`). Mirror of R5 at higher rollup tiers. Extracted `PeriodAttributionMaps.load(periodStart, periodEndExclusive, outMaps, outTotals)` as a shared static utility — all three rollup tiers (week/quarter/year) now use the same SQL. PROMPT_VERSIONs bumped to v3-attribution-verifier-2026-05-28. Dormant tonight (week-of-05-25 has only 2 days; gates closed) but structurally complete.

- [x] **✅ Phase 2. Multi-symbol AttributionVerifier** (commit `04e75d7`). Detects "X, Y, and Z collectively/together/combined/jointly N events" via `MULTI_SYMBOL_TRIGGERS` set + `extractMultiSymbolClaims`. Sums per-symbol counts across the listed subjects; consumed-count tracking ensures the count isn't re-claimed by the single-symbol pass. 4 new tests cover Oxford-comma, "together" with scorer-specific noun, mismatch detection, consumed-count protection.

- [x] **✅ Phase 3. halt_reason_label** (commit `7b79de6`). See above for the structural-data-layer fix discussion.

- [x] **✅ Phase 4. duration_humanized** (commit `c7d79f9`). See above.

- [x] **✅ Phase 5. Pattern-name mislabel verifier** (commit `9f2724c`). See above.

- [x] **✅ Phase 6. 1-day test on 05-12 — PASSED 2026-05-28 ~20:30 EDT.** Synth-05-12 verifier_passed=true with all R2-R5 + Phases 3-5 + 7c/7e/7f fixes active. Gate cleared before Phase 8 build kicked off.

- [ ] **Phase 7. Compound phase-label stitch in DESCRIBE prose.** Observed 2026-05-28 GMRS halt: *"The halt began in pre-market to midday"* — model glued `halt_start_phase_label="in pre-market trading"` + `halt_end_phase_label="around midday"` without a connector verb. Same structural-data-layer pattern as Phase 3 (halt_reason_label) and Phase 4 (duration_humanized): pre-compute a `halt_phase_span_label` field in HaltScorer that combines the two grammatically ("starting in pre-market and resuming around midday" / "lasting through midday from pre-market trading" / etc., depending on phase combination). INTERPRET/DESCRIBE prompts read the unified field; no prompt rule about "don't glue phase labels" needed. ~20 min.

- [ ] **Phase 7b. Background heartbeat daemon in LLM activities.** The heartbeat-timeout fix from earlier today (commit `15af21f` bumped heartbeat 1→5 min on SynthesizeDay + AggregateWeek workflow stubs) is a band-aid that's still in place. The PROPER fix is a background thread inside each LLM-bearing activity that calls `actx.heartbeat()` every 30 sec while the blocking LLM HTTP call runs — same pattern as `MaterializeOrderLifecycleActivityImpl`. After this lands, the heartbeat timeout can drop back to 1 min as a real liveness signal. Apply to: SynthesizeDayActivityImpl, AggregateWeekActivityImpl, AggregateQuarterActivityImpl, AggregateYearActivityImpl. ~30 min.

- [x] **✅ Phase 7c. Time-of-day weight in scoring — SHIPPED.** `BreakdownFmt.timeOfDayWeight(utc)` multiplier (pre_market: 0.85, opening_5min: 1.20, early_session: 1.05, midday: 0.95, late_session: 1.10, closing_5min: 1.15). Applied uniformly across all 7 intraday scorers via `score = baseScore × timeOfDayWeight(ts)`. Unit-tested in `BreakdownFmtTest`.

- [ ] **Phase 7d. Time-of-day diversity in selection (option E) — PUNTED to post-launch.** Decided 2026-05-28 19:40 EDT after Phase 7c landed: the time-of-day-weight multiplier already provides structural attention-window boost (open/close events score 10-12% higher → naturally selected more). Forcing min-per-bucket would risk under-selecting genuine open-driven days. The right call is to observe whether 7c alone produces sufficient diversity in the overnight outputs; if a single-phase-domination pattern persists, revisit 7d post-launch with the data to tune the per-bucket minimums.

- [x] **✅ Phase 7e. TimeInBookDrift baseline gate `MIN_BASELINE_DAYS` 3 → 5 — SHIPPED.** Median stability over noise; one constant in `TimeInBookDriftScorer`.

- [x] **✅ Phase 7f. SweepScorer formula rebalance — SHIPPED.** Changed `log10($notional) × distinct_levels` → `log10($notional) + log10(distinct_levels)` (additive in log space). Both notional and level count contribute without dominating. Re-scored visible on 05-12 + ratified in Phase 9c audit.

- [x] **✅ Phase 8 v1 — PipelineWorkflow + rollup cascade (sequential per-day) — SHIPPED 2026-05-28 ~20:45 EDT** (commit `1352dcb`). Unified entry point: `PipelineWorkflow.run(PipelineInput{dates, pollUntilReady, forceReingest, runRetentionSweep, cascadeRollups})` covers cron, ad-hoc single-day, and multi-day backfill. Per-day chain fires `DailyPipelineWorkflow` as child (existing per-day fan-out preserved). When `cascadeRollups=true`, `computeCascadeScope(dates)` maps the input list to touched week/quarter/year period anchors and fires each rollup; content-addressed at activity level so unchanged inputs no-op cheaply, and the quarter/year gates short-circuit until `MIN_WEEKS_FOR_QUARTER=8` / `MIN_QUARTERS_FOR_YEAR=2` are met. `PipelineWorkflowCascadeTest` covers the pure cascade-scope function (7/7 green: single-date, week-dedup, two-week span, quarter boundary, year boundary, 12-day overnight scenario, chronological ordering).

- [ ] **Phase 8 v2 — Phase A/B overlap parallelization (deferred to post-launch).** v1 runs days sequentially. v2 splits `DailyPipelineWorkflow` into separately-callable `PhaseA` (download/parse/validate/materialize/score/enrich/select — luv, no LLM) + `PhaseB` (DESCRIBE/INTERPRET/SYNTHESIZE — joi, LLM-serialized) child workflows. `PipelineWorkflow` then runs a sliding window: `PhaseA[N+1]` overlaps with `PhaseB[N]`, gated only by the joi `Semaphore(2,fair)` mutex (the one-LLM-workflow-at-a-time rule). Cascade fires sequentially after all `PhaseB` complete. Estimated saving: ~3–4 hr over a 12-day backfill (the Phase A time hidden behind the LLM time of the prior day). Zero saving on a 1-day cron fire. Defer until after launch — v1 already unblocks the cron migration + the overnight resets.

- [ ] **Phase 9-A. Inter-day scorer INTERPRET — implement for both scorers.** Currently `volume_deviation` + `time_in_book_drift` events skip INTERPRET (`TradeWindow.query()`'s ±60-sec framing isn't meaningful for whole-day metrics). Implementation: new `TradeWindow.daySummary(date, symbol)` method reads the day's intraday volume + order-lifetime curve summary; InterpretEventActivityImpl branches on `scorerId IN ('volume_deviation', 'time_in_book_drift')` to use the day-summary query instead of the ±60-sec window. Verifier rules unchanged (same haystack grounding). Catalog has interpretive material for both — "volume built through midday" / "lifetime regime shift began at 09:35 ET and persisted through morning". ~90 min.

- [ ] **Phase 9b. DESCRIBE-side pattern-mislabel check.** Mirror of Phase 5 (INTERPRET) for DESCRIBE. Reuses `AttributionVerifier.extractScorerNounIds`. Allowed-scorer set: event's own scorer_id ∪ co_occurring keys. Plumbed via a new 5-arg `GroundingVerifier.verify(prose, blueprint, breakdown, scorerId, allowedSet)` overload; back-compat 3-arg form preserved. ~15 min.

---

### 🟢 Overnight failure diagnosis — RESOLVED (2026-05-28 morning kick → manual terminate)

**Root cause: not a code or system failure.** The `rerun2-NarrateWorkflow-20260521-163954` was **manually terminated at 17:35:59 EDT** via `docker exec long-exposure-dev-temporal temporal workflow terminate` with explicit reason `"clean slate for full overnight rerun"` (identity `243415@a47c8c8f3891@` — the CLI inside the temporal container). Almost certainly a user-issued cleanup from another session.

**Timeline (EDT):**
- 05:28 — terminal-close incident, restarted via `nohup scripts/rerun-dataset-v2.sh &`
- 05:28 → 16:39 — 11 hours, 7 days completed cleanly (05-12 → 05-20)
- 16:39:54 — driver writes "ScoreWorkflow 05-21 → COMPLETED" + "Starting NarrateWorkflow 20260521"
- 16:39:55 — driver script's last log entry; driver subsequently dies (unknown cause — kill / SIGHUP / OOM, doesn't matter)
- 16:40 → 17:36 — **narrate-21 kept running in Temporal on its own for 56 min**, producing 68 narratives (Temporal is durable — driver death doesn't kill workflows it spawned)
- 17:35:59 — manual `temporal workflow terminate` with reason "clean slate for full overnight rerun" → workflow status → TERMINATED
- 17:36 → 20:39 — 3 hr of nothing in flight; check-in at 20:39 saw the stale state

**Confirmed via:** `WORKFLOW_EXECUTION_TERMINATED` event 427 in the workflow history; `reason="clean slate for full overnight rerun"`. The 68 partial narratives in the DB are consistent with 56 min of progress at ~25 sec/event × 2 concurrent.

**Lesson — the driver architecture itself is the brittle layer.** A bash script wrapping `temporal workflow start --waitForResult` is exactly what Phase 8 (`PipelineWorkflow`, shipped tonight) replaces. Once orchestration moves into Temporal:
- driver-death cannot happen (no external driver)
- terminal-close cannot kill the loop (no external loop)
- the only way to kill it is `temporal workflow terminate` on the orchestrator itself, which is deliberate
- Temporal's history makes the cause auditable in 30 sec (vs the diagnosis dance we just did)

So the answer to "what failed" is "the shell driver". The answer to "what do we do" is "use `PipelineWorkflow`". The right re-kick uses the new orchestrator that doesn't have this failure mode.

**Re-kick plan (post-Phase-7b):**

```bash
# Single-day test first (Phase 8 has never been end-to-end exercised):
docker exec long-exposure-dev-temporal temporal workflow start \
  --task-queue long-exposure-daily-pipeline --type PipelineWorkflow \
  --workflow-id pipeline-test-20260521 \
  --input '{"dates":[[2026,5,21]],"pollUntilReady":false,"forceReingest":false,"runRetentionSweep":false,"cascadeRollups":true}'

# Then the remaining catch-up dates:
docker exec long-exposure-dev-temporal temporal workflow start \
  --task-queue long-exposure-daily-pipeline --type PipelineWorkflow \
  --workflow-id pipeline-catchup-20260528 \
  --input '{"dates":[[2026,5,21],[2026,5,22]],"pollUntilReady":false,"forceReingest":false,"runRetentionSweep":false,"cascadeRollups":true}'
```

`PipelineWorkflow` runs inside Temporal, so it survives terminal-close / driver-death by construction — the exact failure mode that killed the v2 driver cannot recur.

### 🟢 Tonight's plan (post-diagnosis, in order)

1. **Diagnose the 16:40 EDT failure** via the 5 commands above. Find root cause OR rule out (1)/(2)/(3)/(4).
2. **Phase 9-A verification** — grep `INTER_DAY_SCORERS` in `InterpretEventActivityImpl` to confirm the activity branch is actually wired (Catalog entries are wired; activity branch may or may not be — verify before assuming inter-day events get INTERPRET prose).
3. **Phase 7b — Background heartbeat daemon in LLM activities.** Apply the `BackgroundHeartbeat` utility (already exists, used in SYNTHESIZE/AGGREGATE) to `NarrateEventActivityImpl` + `InterpretEventActivityImpl`. Drop the 5-min heartbeat band-aid back to 1 min. ~30 min.
4. **1-day test on 05-21 via `PipelineWorkflow`** — first end-to-end exercise of Phase 8 itself + first test of Phase 7b under load. Confirms cascade fires (weekly-of-05-18 should recompute), quarter/year gates short-circuit cleanly, no child-workflow ID collisions.
5. **Kick the overnight catch-up** via `PipelineWorkflow(dates=[2026-05-21, 2026-05-22])` — the remaining 2 days of the planned week. Replaces the dead shell driver permanently.

Items explicitly NOT doing tonight: Phase 7 (compound phase-label, low rate), Phase 9b (DESCRIBE pattern-mislabel mirror, low rate), Phase 8-v2 parallelization (post-launch).

- [x] **✅ Phase 9c. Score formula sanity audit — DONE 2026-05-28 19:00 EDT.** Audit output captured at top of this section ("Tomorrow's checkpoint"). Cross-scorer normalization (F) PUNTED; sweep-formula fix surfaced as Phase 7f.

### Scoring system — post-launch improvements (parked, NOT for tonight)

Discussion 2026-05-28 surfaced these. Not blocking launch but worth implementing post-launch when there's data history to validate against:

- [ ] **E. Time-of-day diversity in selection** — currently no time-of-day weighting; selection can cluster in the first 5 min (open volatility) and miss midday + close patterns. Implementation: bucket events into session phases (pre_market / early_session / midday / afternoon / late_session) and enforce min-per-bucket. Risk: medium — could under-select genuine open-driven days. Needs cross-day baseline to tune. Punt.

- [ ] **F. Cross-scorer score normalization** — currently per-scorer percentile rank for selection. Each scorer's score is in different units (halt=seconds, large_trade=log10($), pattern=log10(shares)×count). Can't directly compare a halt's "0.95 percentile" to a sweep's "0.95 percentile" in terms of *importance to the day*. Implementation: a z-score / robust-rank normalization across scorers, then global top-N. Big refactor: ~3-4 hours, touches selection, the API's "interesting" ranking, all downstream. Punt to post-launch.

- [ ] **G. Time-of-day weight in scoring (not just selection)** — events at open/close get heavier weight in the score itself. Subtler than (E); a weighting factor on `score *= time_weight(ts)`. Same risks as E. Punt.

- [ ] **H. Per-symbol tier weighting** — a halt on AAPL ranks higher than a halt on a micro-cap, by definition. Implementation: tier classification (mega/large/mid/small/micro from prev_close × shares_outstanding) and a multiplier on scorer scores. Needs shares-outstanding data (we don't have it cleanly). Punt.

- [ ] **I. Audit each scorer's score formula by hand** — sort top-20 per scorer per day, confirm a trader would flag those. Tier 3 in the existing "Hardcode audit." Document why each formula was picked. Punt.

- [ ] **J. Make scorer thresholds data-driven** — Tier 2 in the existing "Hardcode audit." The 12 magic numbers across the 7 intraday scorers. Migration to `scorer_config` table. Independent project; ~half a day. Punt.

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

- [x] **✅ Pattern-name mislabel in INTERPRET — FIXED 2026-05-28 evening** (commit `9f2724c`). New `AttributionVerifier.extractScorerNounIds(prose)` helper reuses the scorer-noun vocabulary (NOUN_TO_SCORER) to detect all scorer_ids mentioned in prose. `InterpretationVerifier` 6-arg overload takes the event's scorer_id; allowed mentions = (event's own scorer_id) ∪ (breakdown.co_occurring.during_event keys); any other = misclassification. PROMPT_VERSION bumped interpret-v9 → v10.
- [x] **✅ AGGREGATE streak-length fabrication — FIXED 2026-05-27** (was 🚩 LAUNCH-RELEVANT). *(Was: the 05-18 weekly rollup, with exactly 1 prior week, claimed "a fifth consecutive week" / "the third straight week where TQQQ…" — fabricated streak length. `SynthesisVerifier` passed it because the streak is a written ordinal + qualitative claim, not a digit it grounds.)* Both fixes shipped in `AggregateWeekActivityImpl` (`PROMPT_VERSION="aggregate-v5-streak-bound-2026-05-27"`): (1) **prompt** now states the actual prior-week *count* and bounds streak language to it ("you have N prior weeks; longest honest streak is N+1 weeks"), and the old "third straight week" examples were removed from the prompt — they were *seeding* the fabrication; (2) **verifier net** — `maxStreakWeeksClaimed(text)` regex (digit + word ordinals via `STREAK_RE`/`ORD_WORDS`) computes the largest "Nth consecutive/straight week" claimed; the retry loop accepts only when `claimedStreak <= priorWeeks + 1`, else re-rolls and finally stores `verifier_passed=false` + a mismatch note. Validated post-fix (1-prior week now renders "a second consecutive period"). The bumped `PROMPT_VERSION` invalidates the content-hash, so tonight's relaunch re-aggregates every week and overwrites the 05-18 known-bad row.
- [x] **✅ Duration pluralization — FIXED 2026-05-28 evening** (commit `c7d79f9`). New `BreakdownFmt.durationSecHumanized(seconds)` formatter with singular/plural unit selection ("1 second" / "5 minutes 30 seconds" / "2 hours 15 minutes"). Wired into LiquidityWithdrawalScorer + IcebergScorer alongside existing `duration_seconds`. INTERPRET/DESCRIBE read `duration_humanized` verbatim; the LLM never formats the unit string itself. Validated on 05-12 Score test: SEPP iceberg → `"22 minutes 57 seconds"`.
- [ ] **Run the orphan prune after the full re-run.** `docs/sql/prune-stale-narrations.sql` — dry-run validated (keep 1817 narr / 1808 interp; remove ~1691 narr + ~328 interp orphans, including 05-08's 1723-row bloat). Run the DELETE block once the re-run finishes so the latest formatted/retried rows are the winners.
- [ ] **Compound phase-label stitch in DESCRIBE prose (verifier-clean, grammatical glue).** Found 2026-05-28 in the week-of-05-11 ground-check (2/9 events affected, all verifier-passed — every number traces):
   - **GMRS halt (05-13)**: *"The halt began in pre-market to midday."* — model glued `halt_start_phase_label="in pre-market trading"` + `halt_end_phase_label="around midday"` without a connector verb. Should be *"spanning pre-market to midday"* or *"began in pre-market and resumed around midday"*.
   - **DRAM sweep (05-14)**: *"Slippage amounted to walked 7.4 bps up."* — fused two prompt templates ("Slippage amounted to X" + "walked X bps up"). Should be *"Slippage walked 7.4 bps up"* or *"Slippage amounted to 7.4 bps."*
   Pure prose-quality (the data layer is fine; the model is constructing an ungrammatical bridge between two grounded facts). *Fix*: one RENDER-prompt iteration that explicitly forbids compound-stitched phase-label / slippage-direction clauses with positive examples for both shapes, plus a 1-day re-narration to confirm. Post-launch — ~2/164 rate is low enough to defer.
- [ ] **`time_in_book_drift` thin-baseline data-quality flag.** Observed 2026-05-28 on 05-15: OVT scored `drift_x=5748.0` with `baseline_window_trading_days=3` — the narration is faithfully grounded (today's avg lifetime 16.5 sec vs baseline median 2.9 ms is arithmetically correct), but a 3-day baseline on a thin bond ETF is statistically too noisy to support a 5,748× signal. The scorer currently gates on `baseline_window >= 3` days; values get progressively wild for thin names at the low end of that window. *Options*: (a) bump `MIN_BASELINE_DAYS` from 3 → 5 in `TimeInBookDriftScorer` so we have more median-stability headroom; (b) keep gate at 3 but add a `baseline_confidence` field that the prose can hedge against ("a 3-day baseline supports the observation but the multiple is sensitive to sample size"); (c) leave it — when the 30-day backfill happens, all baselines firm up automatically. *Decide post-relaunch*: look at the full 11-day dataset and see how many `time_in_book_drift` events have `baseline_window_trading_days < 5`. If it's a long tail, do (a); if isolated, do (c). Distinct from the existing `MIN_DRIFT` tuning in Tier C.
- [ ] **Inter-day scorers have no INTERPRET prose (structural gap).** Found 2026-05-28 in week-of-05-11 audit: `volume_deviation` (TDIC) and `time_in_book_drift` (OVT) events have NO row in `interpretations` — DESCRIBE only. INTERPRET reads `TradeWindow.query()` for ±60 sec around the event timestamp; that framing is intraday-shaped and doesn't have a natural mapping for "today's whole-day metric vs trailing-window baseline." Implications: (1) the per-event UI drill-down for inter-day events will show only DESCRIBE prose, no second paragraph. (2) SYNTHESIZE falls back to DESCRIBE for these events (per architecture docs § "SYNTHESIZE per-event input contract"), so they DO contribute to daily themes, just via the simpler narration. *Decide post-relaunch*: (a) confirm the skip is intentional in `InterpretEventActivityImpl` (vs an oversight); (b) if it's worth adding, design a different INTERPRET shape for inter-day events — read today's full intraday volume / order-lifetime profile rather than ±60 sec around an event ts that's effectively the whole day. The catalog already has interpretive material for volume surges and lifetime regime shifts. Low priority — DESCRIBE-only is workable for the inter-day events for v1.
- [ ] **Borderline interpretive phrasing in INTERPRET (catalog-driver tier).** Observed 2026-05-28: PEG iceberg INTERPRET ended *"consistent with institutional execution seeking to minimize market impact"* — defensible because (i) "consistent with" qualifies vs asserts, and (ii) iceberg's pattern-catalog entry lists "institutional execution + market-impact minimization" as a documented legitimate driver. But it's an intent claim phrased softly. The intent denylist (Tier A1: `manipul/spoof*/front-?run/wash/gam(e|ing|ed)/illegal/fake`) wouldn't catch it; the wording is on the right side of the policy. *Track*: count occurrences across the full re-run; if "consistent with institutional execution" / "intended to minimize" / similar soft-intent constructions exceed a threshold (say ≥ 5% of icebergs / large-trades), tighten the prompt to require purely-observed phrasing ("the iceberg moved 32× the pre-window volume with a 2.4 bps VWAP shift"). Otherwise leave as catalog-driver vocabulary doing its intended job.
- [x] **✅ "Regulatory suspension" phrasing on halts with `halt_reason="NA"` — FIXED 2026-05-28 evening** (commit `7b79de6`). Structural data-layer fix (not a prompt rule): `HaltScorer` now emits `halt_reason_label` alongside `halt_reason`. Label is pre-formatted from the IEX/NMS code (T1 → "news-pending halt", MCB1 → "market-wide circuit-breaker level-1 halt", etc.) and falls back to the SAFE generic `"trading halt"` when the reason is missing/NA/null. INTERPRET/DESCRIBE prompts read this field verbatim; the LLM can't infer "regulatory" because the data never carries that classification absent evidence. Validated on 05-12 Score test: 7 halts with `halt_reason="NA"` → `halt_reason_label="trading halt"`.
- [x] **✅ Cardinal word-form numerals bypassed verifier — FIXED 2026-05-28** (commit pending). Observed 2026-05-28 in 05-12 daily synthesis: 5/5 word-form counts were wrong (eight/ten/six/four/three vs actual 14/12/14/5/4) and bypassed verification entirely because `GroundingVerifier.NUMBER_RE` is digit-only. Generalized across days: every daily synthesis had 2-6 unchecked word-form numerals. **Fix**: added `GroundingVerifier.cardinalWordNumbersIn()` extracting "zero".."thousand" + composite "{unit} hundred|thousand" patterns, wired into all three verifiers (DESCRIBE / INTERPRET / SYNTHESIZE → AggregateWeek/Quarter/Year), bumped SYNTHESIZE/AGGREGATE PROMPT_VERSIONs to v6 to force re-run. Tested on 05-12: new prose shifted from "eight TQQQ events" (wrong) to "TQQQ with 18 incidents" (digit, grounded). Re-running across 05-11..05-18; will re-build week-of-05-11 + week-of-05-18 aggregates after.
- [x] **✅ Intent-denylist false-positive on "gaming" — DROPPED 2026-05-28** (same commit). `gam(?:ing|ed)` regex flagged "gaming stocks like DKNG" (sector use — DraftKings) as intent-claim and burned retry-3× without resolving. Dropped from both `SynthesisVerifier` and `InterpretationVerifier` denylists. Kept: manipul / spoof / front-run / wash-trad / illegal / fake — all unambiguous.
- [x] **✅ Number misattribution in SYNTHESIZE — FIXED 2026-05-28 via structural AttributionVerifier** (commit `bfa3da3`). The bug: prose said "TQQQ which recorded ten distinct order-deletion events" when TQQQ actually had 20 liquidity_withdrawals; "10" passed the existing verifier because it was in the haystack (as the day's halt count), but the (TQQQ, 10, liquidity_withdrawal) triple did not match data. **Fix**: new `AttributionVerifier` class extracts (subject, count, scorer-type) triples from prose via a longest-first noun-phrase regex + bounded-window subject/count lookup; each triple checked against the activity-supplied by_symbol_by_scorer truth map. Three claim shapes covered (subject-led / verb-led / possessive); digit + word-form numerals via the existing `GroundingVerifier.cardinalWordNumbersIn`. Maps populated in `SynthesizeDayActivityImpl.computeDayAggregates` via out-params and passed to the new 5-arg `SynthesisVerifier.verify()` overload — NOT exposed in the LLM-facing JSON (band-aid avoidance per project structural-fix discipline). PROMPT_VERSION bumped synthesize-v6 → v7. 15 unit tests. Reverted earlier same-day attempts (the prompt-instruction + JSON-only band-aids) recorded for posterity in `SynthesizeDayActivityImpl` javadoc.

   *Out of scope, separate todo*: multi-symbol attribution ("DGP, TQQQ, and QQQ collectively generated 49"), AggregateWeek/Quarter/Year attribution (would need period-aggregated truth maps — same pattern, deferred).
- [x] **✅ AttributionVerifier "split between A and B" false-positive — FIXED 2026-05-28 evening** (commit `aeba0e4`). Duplicate of R1 above; see that entry for the structural fix.
- [x] **✅ Derived-sum verification — RESOLVED 2026-05-28 evening** via multi-symbol attribution (commit `04e75d7`). The "DGP, TQQQ, and QQQ collectively generated 49" case is now handled structurally: `AttributionVerifier.extractMultiSymbolClaims` detects `collectively`/`together`/`combined`/`jointly` triggers, sums per-symbol counts across the listed subjects, and grounds the claim. No haystack-padding band-aid needed.
- [x] **✅ 🚩 DESCRIBE/selected_events FK orphaning — FIXED 2026-05-28 evening** (commit `d13fc52`). Was option 3 from the original three: `NarrateEventActivityImpl` + `InterpretEventActivityImpl` now run `UPDATE narratives/interpretations SET selected_id = ?` on cache hit, refreshing the FK to point at the current selected_events row. Same content (same event_hash = same prose); only the FK moves. Verified during R6 test on 05-13: 150/150 selected_events joined a narrative (was 0/150 before the fix); 146/150 joined an interpretation (4 inter-day skips expected). LLM call still skipped on cache hit (the savings are preserved). The overnight rerun and all future re-scores produce a fully UI-visible dataset.

   *Historical impact (pre-fix data)*: 05-11/05-12 still have stale FK orphans from earlier re-scores. The overnight rerun fully re-Scores → re-Narrates those days, which exercises the fix and produces fresh joined data. The orphan prune (`docs/sql/prune-stale-narrations.sql`) collapses old superseded rows after the overnight settles.

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
