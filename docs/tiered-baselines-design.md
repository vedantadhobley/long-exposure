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

Weekly cron (Sun) already planned for AGGREGATE-weekly; add a **monthly cron**
(1st of month, after the prior month's last weekly aggregate exists), paused by
default like the others. Or ad-hoc:
`temporal workflow start --type AggregateMonthWorkflow --input '[2026,5,1]'`.

### 3.5 Frontend

Add `GET /aggregate/month/:monthStart` (and fold weekly+monthly into the
`/aggregate/*` surface from the frontend audit). The browser shows
daily → weekly → monthly themes as a collapsing time hierarchy at the top of the
page.

---

## 4. Retention interplay — why this is the elegant payoff

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

## 5. Build phasing (post-launch, in dependency order)

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

## 6. Open questions / decisions to make at build time

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
