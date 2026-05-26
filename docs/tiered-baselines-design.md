# Tiered Baselines + Monthly Rollups — design (post-launch)

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
  AGGREGATE   monthly themes    [NEW]     1 LLM call/month, reads month's weekly themes
```

**The two stacks connect exactly once, one-directionally:** numeric baselines
feed the detectors → detected events get narrated into the prose stack. The
prose **never** flows back into detection (a detector needs numbers to ground
"22× the median"; it can't and must not ground a decision on an LLM paragraph).

The rest of this doc details each stack's monthly extension.

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

## 3. Prose theme stack (human output) — AGGREGATE-monthly

This is the **genuinely valuable new build**: a monthly themes paragraph, one
level above the weekly `AGGREGATE`. It's an exact mirror of `AggregateWeek*`
(shipped 2026-05-25), one tier up — reads `weekly_aggregate` rows for a month,
writes one paragraph.

### 3.1 Table

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
| Daily SYNTHESIZE | **0** — today's events | — | `narratives` + `interpretations` | the day's themes |
| Weekly AGGREGATE | this week-so-far **+ prior ~3–4 weeks** | weekly prose | `daily_synthesis` (this week) + prior `weekly_aggregate`s | week themes + week-over-week trend |
| Monthly AGGREGATE | this month-so-far **+ prior ~3–6 months** | monthly prose | `weekly_aggregate` (this month) + prior `monthly_aggregate`s | month themes + month-over-month trend |

Mapping your "week + 3–4 weeks prior + 11–12 months prior" intuition: the
multi-resolution *magnitude* shape (recent weeks at daily resolution + the year
at monthly resolution) is the **numeric baseline** (§4.2). The *semantic* shape
is the prose window above — each rollup reads a real span of prior same-tier
periods (so the weekly genuinely analyzes week-over-week trends, not just its own
week). The one place we *don't* go a flat year deep is the **weekly** prose
prompt: instead of stuffing 11–12 months of weekly paragraphs into the weekly
call, that longer arc is the **monthly** tier's job (it reads the months) plus
the numeric long-tier — so each tier reads a *bounded* prior window and the
deeper reach comes from the tier above. Net: timely trend awareness at every
level without any single prompt carrying a year of prose.

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

Each prose rollup reads two things: its **substance** = its own period's
tier-below outputs (the weekly reads *this week's* daily syntheses), **plus** a
**prior-period window** = the prior ~3–4 same-tier rollups, so it can actually
*analyze* week-over-week (or month-over-month) trends rather than describe its
own period in a vacuum. This is the part that closes the latency gap.

**Recompute cadence (this is the bit that answers "would a Monday have last
Friday's data?").** The daily SYNTHESIZE is day-local — Monday's synthesis sees
only Monday. But the **weekly rollup is recomputed *daily* as "week-so-far"**:
the Monday run reads this week's days so far (just Monday) **+ the prior ~3–4
finalized weekly rollups** (which contain last week, i.e. last Friday's themes).
So the cross-day / cross-week trend *does* reach Monday — it lives in the
**weekly-so-far** rollup, not the daily one. Same one tier up: the monthly is
recomputed weekly as "month-so-far." Cheap, because each recompute is **one** LLM
call over short paragraphs, not a re-narration.

Wiring:
- `AggregateWeekActivity` loads this week's `daily_synthesis` rows (substance) +
  the prior ~3–4 `weekly_aggregate` rows, passing the latter under a labelled
  `PRIOR WEEKS (trend context)` heading: *use to analyze week-over-week trends;
  do not present their tickers/numbers as this week's facts.*
- The "week-so-far" recompute is just `AggregateWeekWorkflow` run on each day of
  the open week (it already resolves the ISO week and reads days-so-far). Upsert
  by `week_start` means each daily run **replaces** the in-progress row; the
  Friday run is the finalized week. Archive keeps finalized weeks; the live page
  shows "this week so far."
- **Grounding (load-bearing):** the prior-window paragraphs must enter the
  verifier's allowed-ticker universe + number haystack, else a legitimate "vs
  last week" reference trips the fabrication check (the TSEM-class catch). So the
  window is *opt-in per claim*: a ticker/number is accepted if it traces to **this
  period's inputs OR the prior-period window.**
- **Where we cap it:** the *weekly* prompt reads ~3–4 prior weeks, **not** a flat
  11–12 months of weekly paragraphs. The longer arc is the **monthly** tier's job
  (it reads the months) plus the numeric long-tier. So each tier carries a
  *bounded* prior window; deeper reach comes from climbing one tier up, keeping
  any single prompt small and its grounding surface contained.

### 4.4 How the two windows compose end-to-end

```
day D scoring run
  ├─ intraday scorers      → today's wire only
  └─ inter-day scorers     → recent tier (≤4 wk, daily, exact)
                             + long tier (≤12 mo, monthly, approx)   ⟵ MAGNITUDE history (numbers)
        ↓ both norms baked into each event's breakdown
   DESCRIBE / INTERPRET     → render the pre-computed norms (no lookback in the LLM)
        ↓
   daily SYNTHESIZE         → today's narrations only (day-local)
        ↓
   weekly AGGREGATE         → this week-so-far  + prior ~3–4 weekly rollups   ⟵ SEMANTIC trend, recomputed DAILY
   (recomputed each day)      (so Monday already carries last week / last Friday)
        ↓
   monthly AGGREGATE        → this month-so-far + prior ~3–6 monthly rollups  ⟵ recomputed WEEKLY
   (recomputed each week)
        ↓
   [quarterly / yearly]     → deeper arcs come from climbing one tier further
```

Two histories, both timely: **magnitude** enters once as numbers at scoring time
(rendered per-event); **semantic** trend is carried by the prose rollups, each
reading a *bounded* prior window of its own tier (so trends surface at that
tier's cadence, recomputed live) with the truly long arc handled by the tier
above. No single LLM prompt ever carries a year of prose.

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
3. **AGGREGATE-monthly** (prose) — mirror `AggregateWeek*`. Independent of 1/2;
   can ship as soon as ≥1 month of `weekly_aggregate` rows exists.
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
- **Calendar month vs 4-week month** for AGGREGATE-monthly. Calendar month is
  more legible ("May 2026"); 4-week aligns with the week-aligned retention. Lean
  calendar month for the human-facing prose.
- **`volume_deviation` direction:** still surges-only (v1). Volume *droughts*
  (today ≪ baseline) are a separate, less-narratable signal; revisit with the
  monthly tier (a drought vs monthly-norm may be more meaningful than vs 2-week).
- **Catalog entry for `volume_deviation`** so INTERPRET stops skipping it (it
  currently skips cleanly — see todo.md). Inter-day INTERPRET needs a different
  "context window" than the intraday ±60 s (e.g., the trailing baseline days),
  which is its own design.
