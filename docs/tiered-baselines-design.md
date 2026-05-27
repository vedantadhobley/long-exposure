# Tiered Baselines + Rollup Hierarchy — design (post-launch)

> The prose rollup hierarchy is **day → week → quarter → year** (§8, decided
> 2026-05-27); the earlier "weekly-top, monthly-dropped" framing in §3 is
> superseded. Numeric baselines are §2.

Status: **design, not built.** Captured 2026-05-26. Builds on what shipped in the
2026-05-25/26 work: `VolumeDeviationScorer` (first inter-day scorer), the
`daily_volume_by_symbol` continuous aggregate it reads, `SYNTHESIZE` (daily
themes), and `AGGREGATE`-weekly (`weekly_aggregate`). This doc is the concrete
plan for extending both to longer time horizons.

> Naming: uses the function-name vocabulary (DETECT / DESCRIBE / INTERPRET /
> SYNTHESIZE / AGGREGATE). See `pipeline-architecture.md`.

---

## 1. The core idea: two parallel tiered stacks, one-directional

The system has **two** "rollup" structures that are easy to conflate but do
opposite jobs and must stay separate:

```
  NUMERIC BASELINES  — INPUT to detection. Pure SQL, NO LLM.
  ─────────────────────────────────────────────────────────────
  raw trades  ─▶  daily_volume_by_symbol  ─▶  monthly_volume_by_symbol
  (2-wk TTL,       (per symbol/day;            (per symbol/month;
   huge)            tiny; keep ~1 yr)           tiny; keep forever) [optional]
                         │                            │
                         └──────────────┬─────────────┘
                                        ▼
              inter-day scorers (VolumeDeviation, TimeInBookDrift)
              "today vs trailing-Nd median"  ·  "vs monthly norm"
                                        │  emit events
  ══════════════════════════════════════╪═══════════════════  numbers → events
                                        ▼
  PROSE THEMES  — OUTPUT for humans. LLM; each tier reads the one below.
  ─────────────────────────────────────────────────────────────
  per-event DESCRIBE + INTERPRET
        ▼
  SYNTHESIZE  daily themes      [BUILT]   1 LLM call/day,   reads day's per-event prose
        ▼
  AGGREGATE   weekly themes     [BUILT]   1 LLM call/week,  reads week's daily themes
        ▼
  AGGREGATE   monthly themes    [DEFERRED — see §3 decision; the prose stack tops at weekly]
```

> **Note (decided 2026-05-26):** the prose stack tops out at the **weekly** tier
> (recomputed daily, ~8-week prior window — §3, §4.3). The monthly *prose* rollup
> shown above is **deferred** (a long-reach compression feature for later). The
> monthly *numeric* tier (the baseline cagg) is separate and stays optional (§2.4).

**The two stacks connect exactly once, one-directionally:** numeric baselines
feed the detectors → detected events get narrated into the prose stack. The
prose **never** flows back into detection (a detector needs numbers to ground
"22× the median"; it can't and must not ground a decision on an LLM paragraph).

---

## 2. Numeric baseline stack (detection input)

### 2.1 What exists

`daily_volume_by_symbol` (continuous aggregate, `schema.sql`): one row per
`(symbol, day)` with `total_volume = SUM(size)`, `trade_count`, avg/min/max
price. Hourly refresh policy, 30-day window. `VolumeDeviationScorer` reads a
trailing window of it and computes `percentile_cont(0.5)` (the median) at query
time over the retained daily rows.

### 2.2 The load-bearing insight: the cagg already outlives the raw wire data

A continuous aggregate **decouples from its source once materialized.** When the
2-week retention `drop_chunks` removes old `trades` chunks, the cagg's
already-materialized `(symbol, day)` rows **remain** — provided they were
refreshed before the drop (they are: hourly refresh vs 2-week drop). So
**the daily cagg is the mechanism that lets baselines survive the wire-data
TTL.** Keep this invariant: never let retention drop the cagg's materialization
hypertable, and never set the cagg refresh window shorter than wire retention.

### 2.3 Sizing reality — the daily tier is tiny; keep it ~1 year

One row per symbol per day ≈ 8,700 rows/day ≈ **~2.2 M rows/year** — megabytes.
So:

- **Keep `daily_volume_by_symbol` for ~1 year** (not the 2-week wire TTL). This
  alone gives **exact** trailing medians over any window up to a year, cheaply.
- Concretely: extend the cagg refresh window from `30 days` to `~400 days`, and
  do **not** add the cagg to `RetentionSweepActivity` (it must not be dropped on
  the 2-week schedule). Add a separate, generous cagg retention policy if we
  ever want to cap it (e.g., `add_retention_policy('daily_volume_by_symbol',
  INTERVAL '400 days')`).

**Implication:** the monthly *numeric* tier (2.4) is **optional** — it's a
query-convenience / >1-year-reach feature, not a storage necessity. The
"more data, more compressed" intuition applies to the **raw wire** (which we do
drop), not the daily cagg (already tiny). Build the monthly numeric tier only if
we want baselines reaching back multiple years or a clean "monthly norm"
concept for narration.

### 2.4 Monthly numeric tier (optional) — cagg-on-cagg

If/when we want it, build it as a **hierarchical continuous aggregate** (a cagg
built on the daily cagg; supported TimescaleDB ≥ 2.9):

```sql
CREATE MATERIALIZED VIEW monthly_volume_by_symbol
WITH (timescaledb.continuous) AS
SELECT time_bucket(INTERVAL '1 month', day) AS month,
       symbol,
       sum(total_volume)        AS total_volume,        -- composable
       sum(trade_count)         AS trade_count,         -- composable
       avg(total_volume)        AS avg_daily_volume     -- composable (sum/count)
       -- , percentile_agg(total_volume) AS volume_pctl  -- see note
FROM daily_volume_by_symbol
GROUP BY month, symbol
WITH NO DATA;

SELECT add_continuous_aggregate_policy('monthly_volume_by_symbol',
    start_offset => INTERVAL '13 months',
    end_offset   => INTERVAL '1 day',
    schedule_interval => INTERVAL '1 day',
    if_not_exists => TRUE);
```

**Median gotcha (important):** a true median is **not decomposable** — you can't
compute a monthly median from daily medians, and a cagg can only store
decomposable aggregates. Two ways to get a median-like monthly baseline:

- **(a) Mean fallback** — store `avg_daily_volume` (= sum/count, decomposable).
  Loses robustness to outliers but zero dependencies. Fine for a coarse
  long-horizon baseline.
- **(b) Percentile sketch (recommended if we want real quantiles)** — store a
  `percentile_agg(total_volume)` (TimescaleDB **Toolkit** `uddsketch`/`tdigest`).
  These are **partial aggregates that roll up**, so approximate monthly medians
  /quantiles survive composition. Cost: adds the `timescaledb_toolkit` extension
  dependency. Query with `approx_percentile(0.5, volume_pctl)`.

Cagg-on-cagg ordering caveat: the monthly cagg only sees what the daily cagg has
**materialized**, so the daily refresh must lead the monthly refresh
(`end_offset` ≥ daily's). The policy above lags 1 day, which is safe.

### 2.5 `BaselineProvider` — decouple scorers from the cagg SQL

Currently `VolumeDeviationScorer` inlines its baseline SQL. Introduce the
interface the docs have long referenced, so both tiers are accessible and the
next inter-day scorer reuses it:

```java
public interface BaselineProvider {
    /** Exact trailing-window median of daily volume, from the daily tier. */
    OptionalDouble trailingMedianVolume(String symbol, LocalDate asOf, int days);
    /** How many prior days are actually present in [asOf-days, asOf). */
    int trailingDayCount(String symbol, LocalDate asOf, int days);
    /** Longer-reach norm from the monthly tier (mean or approx-median). Empty if monthly tier absent. */
    OptionalDouble monthlyNormVolume(String symbol, LocalDate asOf, int months);
}
```

- `CaggBaselineProvider implements BaselineProvider` — reads
  `daily_volume_by_symbol` (and `monthly_volume_by_symbol` if built). One
  instance per scoring run, sharing the activity's JDBC connection.
- Refactor `VolumeDeviationScorer` to take a `BaselineProvider` (constructor or
  via `ScoringContext`) instead of inlining SQL. Behavior-preserving for the
  daily tier; gains an optional `monthlyNormVolume` comparison.
- `ScoringContext` gains a `BaselineProvider baselines()` accessor, built once by
  `ScoreEventsActivity` (mirrors how `symbols` is loaded once).

### 2.6 Other inter-day scorers this unlocks

- **`TimeInBookDriftScorer`** — needs its *own* baseline cagg: per-symbol-per-day
  **order-lifetime distribution** (median / quantiles of `order_lifecycle.lifetime_ns`).
  Same tiered shape (daily exact, monthly sketch). "Median order lifetime on SPY
  collapsed from 800 ms to 90 ms vs its 2-week norm." Add a
  `daily_lifetime_by_symbol` cagg over `order_lifecycle` and a matching
  `BaselineProvider` method.

---

## 3. Prose theme stack (human output)

> **SUPERSEDED 2026-05-27 by §8.** This 2026-05-26 decision capped the prose
> stack at the weekly tier and dropped the *monthly* rollup. The current
> direction (§8) instead extends the prose stack into a calendar fractal
> **day → week → quarter → year** (skipping monthly; a quarter = 13 weeks).
> The weekly prior-window widens 8 → 13 (one quarter). Read §8 for the live
> plan; the rest of this §3 (the mirror-AggregateWeek build pattern, the
> recompute-on-child-finalize cadence) still applies to the quarter/year tiers.
> The original 2026-05-26 reasoning is preserved below for the record.
>
> **DECIDED 2026-05-26 — the prose stack tops out at the WEEKLY tier; the monthly
> *prose* rollup is dropped (deferred).** Final shape:
> - **Daily SYNTHESIZE** — day-local: "what happened today," reads only that day's
>   narrations. No prior-period context.
> - **Weekly AGGREGATE** — the top prose tier. **Recomputed daily as
>   "week-so-far"** (not once on Friday), reading *this week's days so far* +
>   **the prior ~8 weekly rollups** (tunable). This single tier carries the
>   semantic trend, timely, with ~2 months of recent reach.
> - **Monthly *prose* rollup — NOT built.** ~8 weekly paragraphs already cover the
>   trend horizon a daily/weekly market readout uses. A monthly prose tier is a
>   pure long-reach *compression* feature — add it later (it's a clean mirror of
>   `AggregateWeek*`, see §3.1–§3.5) only when we want to compress settled older
>   weeks or surface quarterly/yearly semantic columns. **When built it recomputes
>   at a coarse cadence (weekly at most), NOT daily** — its inputs are *settled*
>   prior weeks, so there's nothing to refresh intra-day.
> - **The long timeline is NOT lost:** long-range *magnitude* ("vs its 11-month
>   norm") stays available via the optional monthly *numeric* cagg (§2.4) — a
>   better home for quantitative long-range signal than prose anyway.
>
> §3.1–§3.5 below detail the monthly-prose build; they are **deferred** — kept as
> the spec for when we add quarterly/yearly columns. The *active* prose work is
> the weekly tier's recompute-daily + prior-week window (§4.3).

### 3.1 Table — (deferred monthly-prose build)

```sql
CREATE TABLE IF NOT EXISTS monthly_aggregate (
    month_start      DATE        PRIMARY KEY,   -- 1st of the month
    month_end        DATE        NOT NULL,       -- last week_end that month
    aggregate_text   TEXT        NOT NULL,
    weeks_considered INTEGER     NOT NULL,
    month_aggregates JSONB       NOT NULL,       -- rolled-up per-scorer totals, top symbols, per-week counts
    model_id         TEXT        NOT NULL,
    prompt_version   TEXT        NOT NULL,
    verifier_passed  BOOLEAN     NOT NULL,
    verifier_notes   JSONB,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

Kept indefinitely (tiny — one row/month). Add it to the schema's "kept forever"
list alongside `daily_synthesis` / `weekly_aggregate`.

### 3.2 Activity + workflow (mirror AggregateWeek)

- `AggregateMonthActivity` (interface) + `AggregateMonthActivityImpl`:
  1. Resolve the calendar month of `anyDateInMonth` (`month_start` = 1st,
     `month_end` exclusive = next month's 1st).
  2. Load that month's `weekly_aggregate` rows (`week_start >= month_start AND
     week_start < nextMonth`), chronological.
  3. Roll the per-week `week_aggregates` up to the month: total events,
     per-scorer totals, top symbols across the month, per-week event counts.
  4. Build the prompt: monthly rules + month metadata + the month's weekly-theme
     paragraphs.
  5. One LLM call, `SamplingParams.AGGREGATE` (the Qwen reasoning preset — reuse).
  6. Verify with `SynthesisVerifier` reused one level up: allowed tickers =
     union of `SynthesisVerifier.extractTickers()` across the month's weekly
     aggregates; number haystack = weekly-aggregate texts + month aggregates.
     (This inherits the dotted-ticker fix automatically.)
  7. Upsert `monthly_aggregate` (PK `month_start`).
- `AggregateMonthWorkflow` + Impl: single activity call on
  `NARRATION_TASK_QUEUE`; shares the 2-slot LLM cap and the one-LLM-workflow rule.
- Register both in `WorkerMain` (workflow list + narration-queue activity list).

### 3.3 Prompt

Same shape as the weekly `SYSTEM_PROMPT`, raised a level: "themes of a trading
**month** … reads the month's **weekly** theme paragraphs … identify patterns
spanning *weeks* (regimes building across the month, symbols recurring
week-to-week, monthly regime shifts) … ground every ticker/number in the weekly
paragraphs + month metadata … no external-news/intent/severity, no cross-month
comparison." `PROMPT_VERSION = "aggregate-month-v1-…"`.

### 3.4 Cadence

**Superseded by §4.3's recompute model** — read that for the final word. In
short: rather than a once-weekly / once-monthly cron, each rollup is recomputed
at its **sub-period cadence** ("week-so-far" recomputed daily, "month-so-far"
recomputed weekly), upserting by `week_start` / `month_start` so each run
replaces the in-progress row and the period-end run is the finalized one. Plus
ad-hoc: `temporal workflow start --type AggregateMonthWorkflow --input '[2026,5,1]'`.

### 3.5 Frontend

Add `GET /aggregate/month/:monthStart` (and fold weekly+monthly into the
`/aggregate/*` surface from the frontend audit). The browser shows
daily → weekly → monthly themes as a collapsing time hierarchy at the top of the
page.

---

## 4. Tiered context windows — reach, resolution, and the wiring

The question this section answers: **how far back does each level reach, at what
resolution, and how does that context get assembled and handed to the scorer /
narrative driver?** The governing principle (decided 2026-05-26, refined after
the "a Monday wouldn't have last Friday's data" latency point):

> **Two kinds of trend history, two mechanisms.** *Quantitative* magnitude
> history ("4.7× its 11-month volume norm") is carried by the **numeric**
> baselines that feed the detectors — per symbol, per metric, exact-recent +
> coarse-long. *Qualitative / semantic* trend history ("activity shifted from
> halts to layering," "these names keep recurring") is carried by the **prose**
> rollups — and prose carries it **two complementary ways**: (a) **climbing the
> tiers** (the month summarizes its weeks; the quarter its months), and (b) a
> **prior-period window** at each tier — each rollup reads a *meaningful* span of
> prior same-tier periods so trends are visible **at that tier's own cadence**,
> not only retrospectively when the tier above eventually runs.

Why both (a) and (b): climbing the tiers alone has a **latency gap** — if the
weekly rollup only sees its own week, nothing notices a week-over-week trend
until the *monthly* runs (weeks later). The prior-period window closes that:
a weekly rollup that reads the prior ~3–4 weeks can say "the third straight week
of rising halts" *this week*. It's affordable because rollups read prior
**paragraphs** (~700 chars each), not raw data, and they're 1 LLM call — so we
also **recompute them at their sub-period cadence** (the weekly rollup
recomputed *daily* as "week-so-far"; the monthly *weekly* as "month-so-far") to
keep the live view current. The numeric side still carries the per-symbol
magnitude reach (cheap, exact, groundable); the prose side carries the semantic
arcs, timely at every tier.

### 4.1 Reach + resolution matrix

| Driver | Lookback reach | Resolution | Source | What it enables |
|---|---|---|---|---|
| Intraday scorers (×7) | **0** — today only | — | wire tables / `order_lifecycle` | per-day patterns |
| Inter-day scorer — *recent tier* | ~2–4 weeks | **daily** (exact median) | `daily_volume_by_symbol` | "vs its 4-week norm" |
| Inter-day scorer — *long tier* | ~12 months | **monthly** (mean / sketch) | `monthly_volume_by_symbol` | "vs its 11-month norm" |
| Daily SYNTHESIZE | **0** — today's events | — | `narratives` + `interpretations` | the day's themes (day-local) |
| Weekly AGGREGATE (top prose tier; recomputed **daily** as week-so-far) | this week-so-far **+ prior ~8 weeks** | weekly prose | `daily_synthesis` (this week) + prior `weekly_aggregate`s | week themes + ~2-month trend, **timely** |
| ~~Monthly AGGREGATE (prose)~~ | **DEFERRED** — not built (§3) | — | — | long-reach *semantic* compression, when quarterly/yearly columns wanted |

So the **prose** semantic trend is carried by a single tier — the weekly rollup,
recomputed daily, reading ~8 prior weeks — giving ~2 months of timely
week-over-week trend. The **numeric** side (§4.2) independently carries the
per-symbol *magnitude* reach out to a year (the optional monthly cagg). Your
"week + prior weeks + prior months" intuition splits cleanly across the two: the
prose handles the recent ~8-week semantic arc; the numbers handle the
year-deep magnitude arc. No monthly *prose* tier needed until we want genuinely
long semantic columns — and then it's a clean add (§3, deferred).

### 4.2 Numeric side — the multi-resolution baseline window (detail + wiring)

A scorer on day **D** assembles a two-tier window, both ending just before D:

- **Recent tier** — the last ~N days from `daily_volume_by_symbol`, queried with
  `percentile_cont(0.5)` for an **exact** median. This is what `VolumeDeviationScorer`
  does today (over the retained ~2 weeks; extend the cagg horizon to widen it).
- **Long tier** — the last ~12 months from `monthly_volume_by_symbol`
  (cagg-on-cagg, §2.4), giving an approximate monthly norm (mean, or a toolkit
  percentile sketch). Cheap (one row/symbol/month) and reaches back a year+.

Assembled once per scoring run by the `BaselineProvider` (§2.5), which gains the
long-tier method:

```java
public interface BaselineProvider {
    OptionalDouble trailingMedianVolume(String symbol, LocalDate asOf, int days);   // recent tier (exact)
    int            trailingDayCount   (String symbol, LocalDate asOf, int days);
    OptionalDouble monthlyNormVolume  (String symbol, LocalDate asOf, int months);  // long tier (approx)
}
```

Wiring per scoring run (in `ScoreEventsActivity`):
1. Build one `CaggBaselineProvider` over the activity's JDBC connection (mirrors
   how the `symbols` cache is loaded once).
2. Each inter-day scorer calls both tiers and writes **both norms into the
   breakdown** (the grounding contract): e.g.
   `{ todays_volume, week4_median, week4_deviation_x, month12_norm, month12_deviation_x }`.
3. DESCRIBE/INTERPRET then narrate from those pre-computed fields — the LLM never
   divides or reaches for history; it just renders "4.7× its 4-week median and
   2.1× its 11-month norm."

Two-tier so a quiet symbol that only recently became active doesn't get a noisy
ratio (recent tier thin → fall back to / cross-check against the monthly norm),
and a symbol with a genuine multi-month regime shift surfaces against the long
tier even when the last 4 weeks look "normal."

### 4.3 Prose side — own period + a real prior-period window (detail + wiring)

**Decided shape:** the weekly rollup is the *single* prose trend tier. It reads
its **substance** = this week's daily syntheses, **plus** a **prior-week window**
= the prior **~8** weekly rollups (tunable), so it analyzes week-over-week trends
rather than describing its own week in a vacuum. The daily synthesis stays
day-local; there is **no monthly prose tier** (deferred, §3).

**Recompute cadence (answers "would a Monday have last Friday's data?").** The
daily SYNTHESIZE is day-local — Monday's synthesis sees only Monday. But the
**weekly rollup is recomputed *daily* as "week-so-far"**: the Monday run reads
this week's days so far (just Monday) **+ the prior ~8 finalized weekly rollups**
(which contain last week, i.e. last Friday's themes). So the cross-day /
cross-week trend *does* reach Monday — it lives in the **weekly-so-far** rollup,
not the daily one. Cheap: each recompute is **one** LLM call over ~9 short
paragraphs (this-week-so-far + ~8 priors), not a re-narration.

Wiring:
- `AggregateWeekActivity` loads this week's `daily_synthesis` rows (substance) +
  the prior **~8** `weekly_aggregate` rows, passing the latter under a labelled
  `PRIOR WEEKS (trend context)` heading: *use to analyze week-over-week trends;
  do not present their tickers/numbers as this week's facts.*
- **Stateless rebuild, NOT incremental (decided 2026-05-26).** Each daily
  recompute reads this week's *actual daily syntheses* (re-read fresh, all days
  so far incl. the new day) — it does **not** read its own prior "week-so-far"
  output. Reading its own prior version would compound summaries
  (summary-of-summary drift, lossier each day); the daily syntheses are kept
  forever, so we always rebuild from source. Net the recompute is itself
  multi-resolution: *this* week at day granularity + prior weeks at week
  granularity.
- The "week-so-far" recompute is just `AggregateWeekWorkflow` run on each day of
  the open week (it already resolves the ISO week and reads days-so-far). Upsert
  by `week_start` means each daily run **replaces** the in-progress row; the
  last run of the week is the finalized week. Archive keeps finalized weeks; the
  live page shows "this week so far."
- **Grounding (load-bearing):** the prior-window paragraphs must enter the
  verifier's allowed-ticker universe + number haystack, else a legitimate "vs
  last week" reference trips the fabrication check (the TSEM-class catch). So the
  window is *opt-in per claim*: a ticker/number is accepted if it traces to **this
  period's inputs OR the prior-week window.**
- **Window size:** start at **~8 weeks** (≈2 months of trend). 8 short paragraphs
  is ~2K tokens — trivial. Tunable to ~16 (~4 months) if wanted. Beyond that,
  *that's* when a monthly prose tier earns its keep (compression — 12 monthly
  paragraphs reach a year where 52 weekly ones would bloat); until then, no
  monthly prose.

### 4.4 How it composes end-to-end

```
day D scoring run
  ├─ intraday scorers      → today's wire only
  └─ inter-day scorers     → recent tier (≤4 wk, daily, exact)
                             + long tier (≤12 mo, monthly cagg, approx)   ⟵ MAGNITUDE history (numbers)
        ↓ both norms baked into each event's breakdown
   DESCRIBE / INTERPRET     → render the pre-computed norms (no lookback in the LLM)
        ↓
   daily SYNTHESIZE         → today's narrations only (day-local)
        ↓
   weekly AGGREGATE         → this week-so-far  + prior ~8 weekly rollups   ⟵ SEMANTIC trend, recomputed DAILY
   (recomputed each day)      (so Monday already carries last week / last Friday)

   [monthly prose rollup]   → DEFERRED — long-reach semantic compression, add when
                              quarterly/yearly columns are wanted (§3)
```

Two histories: **magnitude** enters once as numbers at scoring time (rendered
per-event, reaching a year via the numeric monthly cagg); **semantic** trend is
carried by the *single* weekly prose tier, recomputed daily over a ~8-week prior
window. No monthly prose tier, no prompt carrying a year of prose, and the long
*magnitude* timeline is preserved on the numeric side.

---

## 5. Retention interplay — why this is the elegant payoff

| Layer | Size | Retention | Purpose |
|---|---|---|---|
| raw wire (`orders_*`, `trades`, …) | ~13 GB/day | **2 full weeks** (week-aligned) | re-score substrate; expensive |
| `order_lifecycle` (derived) | large | 2 full weeks | inter-day lifetime baselines' raw source |
| `daily_volume_by_symbol` (cagg) | ~MB/yr | **~1 year** | exact trailing-window baselines |
| `monthly_volume_by_symbol` (cagg) | ~KB/yr | **forever** (optional) | multi-year baseline reach |
| `narratives` / `interpretations` | KB/day | **forever** | the per-event archive |
| `daily_synthesis` / `weekly_aggregate` / `monthly_aggregate` | KB | **forever** | the themes archive |

The expensive thing (raw wire) gets the short TTL; **everything derived is tiny
and kept long/forever.** So we drop the firehose yet lose nothing that matters:
detectors still compare against months of history (via the caggs, which outlive
the wire data), and the site still shows every day/week/month's story back to
launch. That's the whole point of the tiering.

---

## 6. Build phasing (post-launch, in dependency order)

1. **Extend daily cagg retention + refresh window** (`30 days` → `~400 days`),
   and confirm `RetentionSweepActivity` never touches it. *Smallest, highest
   leverage — gives exact 1-year baselines immediately, no new objects.*
2. **`BaselineProvider` + refactor `VolumeDeviationScorer`** onto it.
   Behavior-preserving; decouples scoring from SQL; sets up reuse.
3. **Weekly rollup: recompute-daily + prior-week window** — make `AggregateWeek`
   read this-week-so-far + the prior ~8 `weekly_aggregate` rows (with the
   verifier grounding extension, §4.3), and run it daily on the open week
   (upsert-by-`week_start`). *This is the active prose-trend work.* (The monthly
   *prose* rollup is **deferred** — §3 — a later compression feature, not in this
   sequence.)
4. **`TimeInBookDriftScorer` + its lifetime cagg** — the second inter-day scorer,
   using the same `BaselineProvider` pattern.
5. **Monthly numeric tier (`monthly_volume_by_symbol`)** — only if we want
   multi-year reach or hit daily-cagg query-perf limits. Decide mean-fallback vs
   toolkit percentile sketch then.

Items 1–3 are each a few hours; 4 is a new scorer + cagg; 5 is optional.

---

## 7. Open questions / decisions to make at build time

- **Toolkit dependency?** Percentile sketches (`timescaledb_toolkit`) give proper
  approximate quantiles at the monthly tier; mean is dependency-free. Lean
  toward mean unless a scorer genuinely needs robust long-horizon quantiles.
- **Daily cagg horizon:** 1 year vs forever. 1 year ≈ 2.2 M rows (trivial);
  forever is also fine. Pick when setting the retention policy.
- **Quarter/year period definition** (replaces the old "calendar month vs
  4-week" question — there's no monthly prose tier; see §8). Use **ISO-week
  multiples**: quarter = 13 ISO weeks, year = 4 quarters (52 weeks), all aligned
  to the week-aligned boundary the retention sweep already uses — consistent and
  drift-free, at the cost of "Q2 2026" not landing exactly on the calendar
  quarter. Decide ISO-13-week vs calendar-quarter at build (lean ISO for
  alignment with the rest of the system).
- **`volume_deviation` direction:** still surges-only (v1). Volume *droughts*
  (today ≪ baseline) are a separate, less-narratable signal; revisit with the
  monthly tier (a drought vs monthly-norm may be more meaningful than vs 2-week).
- **Catalog entry for `volume_deviation`** so INTERPRET stops skipping it (it
  currently skips cleanly — see todo.md). Inter-day INTERPRET needs a different
  "context window" than the intraday ±60 s (e.g., the trailing baseline days),
  which is its own design.

---

## 8. The calendar rollup hierarchy (day → week → quarter → year) + the cascade

> **Decided 2026-05-27 (direction; build post-launch).** Supersedes §3's
> "weekly is the top tier, monthly dropped" framing and the §7 "calendar month
> vs 4-week month" question. The prose stack becomes a **calendar fractal**:
> `daily SYNTHESIZE → weekly AGGREGATE → quarterly rollup → yearly rollup`. A
> quarter = **13 ISO weeks** (52/4); a year = **4 quarters**. The yearly tier is
> the capstone — a "year in IEX microstructure" retrospective, which is the
> long-exposure idea at maximum exposure time, for ~1 LLM call/year.

### 8.1 The fractal — every tier is the same shape

Each tier produces one prose paragraph per period by reading **(a)** its own
period's children *fresh* + **(b)** a prior-window of same-tier siblings for
trend context, content-addressed so the recompute is incremental. This is
exactly today's `AggregateWeek` generalized one axis:

| Tier | Period | Reads (children, fresh) | Prior-window (trend) | Recompute cadence | Finalizes |
|---|---|---|---|---|---|
| SYNTHESIZE | day | that day's per-event INTERPRETs | — (day-local) | per pipeline run | end of day |
| AGGREGATE-week | ISO week | that week's daily syntheses | prior **13 weeks** (a quarter) | every day the week is open ("week-so-far") | week close |
| AGGREGATE-quarter | quarter (13 wk) | that quarter's weekly rollups | prior **4 quarters** (a year) | every time a constituent **week finalizes** ("quarter-so-far") | quarter close (its last week) |
| AGGREGATE-year | year (4 q) | that year's quarterly rollups | prior years (optional) | every time a constituent **quarter finalizes** | year close |

The unifying rule: **a tier recomputes whenever one of its children finalizes**
(the lowest tier, weekly, recomputes daily because its child is the day). So
each period is a live "period-so-far" while open and frozen once closed —
generalizing your "partial recomputes, completed is final" intuition up the
whole stack. The prior-window widening (`PRIOR_WEEKS` 8 → 13) makes the weekly
trend horizon exactly one quarter, which is why the quarter reads cleanly as
"the trend the weeks were already tracking."

### 8.2 The cascade — detection is built, the trigger is not

Every tier's `content_hash` already covers **its children + its prior-window**
(built for weekly; the quarter/year tiers mirror it). So staleness is
*detected* automatically: change a historical week and the downstream weeks
that cite it (prior-window), the quarter that contains it, and that quarter's
year all get a different hash. **What's missing is the *trigger*** — nothing
re-runs them. Forward (nightly) operation only touches the *current* period at
each tier, so this gap is invisible day-to-day; it only bites on a **historical
backfill / re-synthesis / cross-history prompt bump** (extending the archive
backward, or post-launch reprocessing).

The cascade is **two-dimensional**:

- **Vertical (up-tier):** changed day → its week → that week's quarter → that
  quarter's year.
- **Horizontal (same-tier prior-window):** changed week → the next 13 weeks that
  cited it → *their* quarters → … (and a changed week's new output can shift the
  week after it, rippling forward through the prior-window chain).

**The elegant trigger — coarse re-run, hash-pruned.** We do **not** need a
fine-grained dependency graph. A `CascadeAggregate(fromDate)` driver re-runs,
**bottom-up by tier**, *every* period from `fromDate` to now at each tier
(weeks, then quarters, then year), and the **content-hash skip prunes it to the
actual work**: a period whose inputs are byte-identical is a no-op, and — key —
when a recompute produces *identical* prose (the change didn't actually move the
trend), the downstream hash is unchanged and **the ripple terminates on its
own.** So the driver is dumb and safe; the hashes make it cheap and
self-limiting. Invoke it as the tail of any historical backfill.

Pseudo-shape:

```
CascadeAggregate(fromDate):
  for each ISO week W from week(fromDate) .. current:   AggregateWeek(W)     # hash-skips unchanged
  for each quarter Q from quarter(fromDate) .. current: AggregateQuarter(Q)  # hash-skips unchanged
  for each year Y from year(fromDate) .. current:       AggregateYear(Y)     # hash-skips unchanged
```

Bottom-up ordering matters: weeks must settle before the quarters that read
them, quarters before the year. Within a tier, chronological (a week's
prior-window needs the earlier weeks finalized first — same constraint
`rerun-dataset.sh` already honors).

### 8.3 Numeric side cascades differently (and for free)

The numeric baseline stack (the caggs, §2) does **not** need this driver: a
backfill into a historical day is picked up by re-running
`refresh_continuous_aggregate` over the affected range (TimescaleDB
recomputes the materialized buckets). So the cascade driver is a **prose-stack**
concern only; the numeric tier's "cascade" is a range refresh.

### 8.4 Retention reframe — "track a quarter" is mostly free; don't conflate it with wire retention

"Maintain a quarter" splits into two very different costs:

- **The rollup/narrative product** (daily synthesis + weekly/quarterly/yearly
  rollups + per-event narratives/interpretations) is **kept indefinitely
  already** and is kilobytes/period. "Tracking a quarter" — or a year, or
  forever — for *rollup* purposes is **free**; it's just widening prior-windows
  + adding the two tiers.
- **The wire substrate** (13 wire hypertables + `order_lifecycle` +
  scored/selected) is the only heavy thing, and it's needed *only to re-score*.
  A full quarter of wire ≈ 13 wk × 5 d × ~13 GB ≈ **~850 GB**. **Don't pay that
  to "track a quarter"** — the quarter's *value* (the prose + the baselines)
  doesn't need it. Keep wire retention short (the current week-aligned window,
  sized to how far back we'd want to re-score), and let the rollups + the
  ~1-year cagg carry the long horizon. `RETENTION_WEEKS` (wire) and the
  rollup/prior-window horizons are **independent knobs**; the quarter/year model
  changes the latter, not necessarily the former.

So the only real disk decision is the *wire re-score window*, unchanged by this
design. (This supersedes the framing that "retention = 2 weeks" bounds the
rollups — it bounds only the wire substrate.)

### 8.5 Build phasing (post-launch)

1. **Widen the weekly prior-window** `PRIOR_WEEKS` 8 → 13 (one constant; the
   weekly trend horizon becomes a quarter). Cheap.
2. **`AggregateQuarterActivity` + `AggregateQuarterWorkflow`** — a near-verbatim
   mirror of `AggregateWeek`: reads the quarter's ≤13 weekly rollups + prior 4
   quarters, content-addressed (`quarterly_aggregate.content_hash`), recompute
   on week-finalize. New `quarterly_aggregate` table.
3. **`AggregateYearActivity` + `AggregateYearWorkflow`** — same mirror, reads 4
   quarters; recompute on quarter-finalize. New `yearly_aggregate` table.
4. **`CascadeAggregate(fromDate)`** driver (§8.2) — wired as the tail of the
   backfill path (`rerun-dataset.sh` / a backfill workflow), not the nightly
   path. The nightly path keeps touching only the current period at each tier.
5. **Wire the quarter/year child calls into `DailyPipelineWorkflow`** the same
   way AGGREGATE-week is: after the weekly rollup, conditionally fire the
   quarter rollup when the just-finalized day closes a week, and the year rollup
   when it closes a quarter. (Or keep them in the cascade driver + a lightweight
   "did a period just close?" check — decide at build.)

All of items 2–5 are the AggregateWeek pattern repeated; the only genuinely new
code is the `CascadeAggregate` driver, and even that is a loop over existing
content-addressed activities. Effort: each tier ~half a day; the cascade driver
~half a day; the prior-window widening minutes.
