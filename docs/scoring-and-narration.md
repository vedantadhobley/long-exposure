# Scoring + Narration — Design Reference

Distilled from the project-positioning + design discussion (captured 2026-05-11). The DEEP+ pivot recorded in `docs/decisions.md` (2026-05-11 entry) makes order-level pattern detection the actual product surface. This doc is the design reference the EventScorer and LLM prompt engineering will work against — the two pieces of work expected to be the hardest in the project.

## Framing — what TOPS gives us vs what DEEP+ unlocks

Where TOPS gives you **the result** (best bid, best ask, last trade, halts), DEEP+ gives you **the causal chain that produced that result** — every individual order's full lifecycle from submission through cancellation or execution.

That distinction is the whole product:

- A TOPS-only narrative is "spread widened to 3× normal at 11:45 in SPY." Any feed could publish that. Generic market commentary.
- A DEEP+ narrative is "8 orders posted on the bid and cancelled within 50ms of each other at 11:45 in SPY, immediately before SPY's spread widened 3×. Classic spoof-shaped post-cancel cluster." That's only possible with order-by-order data.

The IEX brand is transparency. The distinctive product is one that *uses* the transparency IEX commits to.

## Pattern catalog — what the EventScorer should detect

These are the high-signal microstructure patterns that DEEP+ uniquely makes visible. Each pattern should be a scorer module that takes the order-event stream and emits scored events with a transparent JSON breakdown.

### 1. Rapid post-cancel clusters (spoofing-shaped)

**Signal**: orders posted and cancelled within milliseconds, particularly clustered on one side of the book ahead of price movement on the other side.

**Detection sketch**: per symbol, sliding-window count of (Add Order → Order Delete) pairs where the order's lifetime is below some threshold (50ms? 100ms? — needs tuning). High counts + same side + concentrated in time = candidate spoofing pattern.

**Caveats**: legitimate market-makers also post-cancel frequently. The signal isn't post-cancel per se — it's post-cancel *ahead of opposite-side price movement*. Score should weight directional context.

### 2. Liquidity withdrawal

**Signal**: market makers simultaneously pulling quotes on both sides of the book ahead of anticipated volatility (news, halts, earnings windows).

**Detection sketch**: per-symbol per-second, count Order Deletes on the displayed book at the top 5 price levels. Compare to per-symbol baseline of typical deletion rate. Two-sided spike = withdrawal candidate.

**Cross-reference**: should correlate with subsequent spread widening (visible in derived top-of-book) and/or halt events (from `status_events`).

### 3. Layering

**Signal**: multiple orders posted at different price levels creating artificial depth on one side, then cancelled simultaneously.

**Detection sketch**: detect groups of Adds across N price levels from the same time window (~tens of ms) on the same side, followed by group-cancel within a similar window. The "same originator" signal is partly recoverable from synchronized timing even though IEX doesn't expose participant IDs in the public feed.

### 4. Sweep events

**Signal**: a single aggressive order executing against multiple resting orders across price levels — visible as a burst of Order Executed messages with the same incoming side/timestamp consuming multiple levels.

**Detection sketch**: per-symbol, find clusters of Order Executed messages within a ~10ms window that hit ≥3 price levels on the same side. Sum the executed size and price-walk; large sweeps are intrinsically narratable ("a single buy order swept $X of liquidity across N levels in SPY").

### 5. Iceberg detection

**Signal**: repeated small executions at the same price suggesting a large hidden order being worked (the "tip of the iceberg" displayed; the bulk hidden in reserve).

**Detection sketch**: per (symbol, price level), count of Order Executed events resulting in equal-size fills over a sustained period. A consistent fill size repeating ~dozens of times at one level over minutes is the canonical iceberg shape.

**Note**: IEX's TOPS feed only shows the *displayed* portion of orders. DEEP+ has the same limitation by design (non-displayed reserve is not exposed). So this detection is necessarily inferential, not direct.

### 6. Time-in-book distribution shifts

**Signal**: median or distribution of order lifetimes (Add → Delete or Add → Execute) changing materially within a session — e.g., median order lifetime collapsing from 800ms to 50ms signals changed market behavior (often: a regime change in active participants).

**Detection sketch**: per-symbol-per-hour distribution of order lifetimes. Compare to per-symbol baseline distribution from the 30-day rolling window. KL divergence or simple median/quantile drift triggers a scored event.

---

---

## Scoring architecture

How the pattern catalog above maps onto code. Three abstractions:

```
Raw events                  Derived events
(hypertable rows)           (scored_events rows)
─────────────────           ─────────────────────
trades, orders_*,           one per "thing worth
status_events, etc.         narrating"
        │                            ▲
        │   ┌─────────────────┐      │
        ├──▶│ Intraday scorer │──────┤   (only needs today's data)
        │   └─────────────────┘      │
        │                            │
        │   ┌─────────────────┐      │
        └──▶│ Interday scorer │──────┘   (also reads BaselineProvider)
            └─────────────────┘
                    │
                    ▼
            BaselineProvider
            (cagg / view query; returns empty if no history yet)
```

Raw events live in the existing 13 hypertables and don't move. Scored
events are anything worth narrating — could be a single row (halt, large
trade) or a synthesized pattern across many rows (sweep, post-cancel
cluster). Scorers are the extension point.

### The interfaces

```java
public interface EventScorer {
    /** Stable id, e.g. "halt", "large_trade", "sweep", "post_cancel_cluster". */
    String id();

    /** Whether this scorer consults BaselineProvider. */
    default boolean needsBaselines() { return false; }

    /** Scan source data for the day and emit zero or more scored events. */
    Stream<ScoredEvent> score(ScoringContext ctx);
}

public record ScoringContext(
        LocalDate         tradingDate,
        Connection        conn,
        BaselineProvider  baselines       // EmptyBaselineProvider when no history exists
) {}

public record ScoredEvent(
        LocalDate  tradingDate,
        String     symbol,
        Instant    ts,                    // event anchor time
        Instant    tsEnd,                 // null for instantaneous; set for durational
        String     scorerId,              // matches EventScorer.id()
        double     score,
        JsonNode   breakdown,             // transparency JSON — see "grounding contract"
        JsonNode   sourceRefs             // array of {"table":...,"ts_nanos":...} pointers
) {}

public interface BaselineProvider {
    Optional<Double>          dailyVolumeMedian(String symbol);
    Optional<Double>          tradeCountMedian(String symbol);
    Optional<Double>          averageSpreadMedian(String symbol);
    Optional<HaltFrequency>   haltFrequency(String symbol);
    // extends per metric as scorers need them
}
```

Implementations live in `parser/src/main/java/com/longexposure/scoring/`:

```
scoring/
  ├── EventScorer.java                   (interface)
  ├── ScoredEvent.java                   (record)
  ├── ScoringContext.java                (record)
  ├── BaselineProvider.java              (interface)
  ├── EmptyBaselineProvider.java         (used until history exists)
  ├── CaggBaselineProvider.java          (backed by daily_volume_by_symbol cagg)
  ├── EventScorerRegistry.java           (list of all registered scorers)
  └── scorers/
      ├── HaltScorer.java                (intraday)
      ├── LargeTradeScorer.java          (intraday)
      ├── SweepScorer.java               (intraday)
      ├── PostCancelClusterScorer.java   (intraday)
      ├── LayeringScorer.java            (intraday)
      ├── IcebergScorer.java             (intraday)
      ├── LiquidityWithdrawalScorer.java (intraday, baseline = rolling N-min window today)
      ├── TimeInBookDriftScorer.java     (interday, BaselineProvider)
      └── VolumeDeviationScorer.java     (interday, BaselineProvider)
```

### The schema

```sql
CREATE TABLE scored_events (
    event_id      BIGSERIAL    PRIMARY KEY,
    trading_date  DATE         NOT NULL,
    symbol        TEXT         NOT NULL,
    ts            TIMESTAMPTZ  NOT NULL,
    ts_end        TIMESTAMPTZ,                       -- nullable for instantaneous
    scorer_id     TEXT         NOT NULL,
    score         DOUBLE PRECISION NOT NULL,
    breakdown     JSONB        NOT NULL,
    source_refs   JSONB        NOT NULL,
    scored_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    pipeline_run  UUID                               -- nullable; ties back to pipeline_runs
);
CREATE INDEX scored_events_date_score_idx ON scored_events (trading_date, score DESC);
CREATE INDEX scored_events_symbol_idx     ON scored_events (symbol, ts DESC);
CREATE INDEX scored_events_scorer_idx     ON scored_events (scorer_id, trading_date);
```

Not a hypertable. Output volume is small (thousands per day across all
scorers, not millions). Standard B-tree indexes are enough.

Idempotency: scoring a date is re-runnable. `ScoreEventsActivity`
pre-cleans with `DELETE FROM scored_events WHERE trading_date=?` before
running — much faster than the parse pre-clean since rowcount is small.

### Mapping the pattern catalog to scorers

| Catalog # | Pattern | Type | Source tables | Detection sketch |
|---|---|---|---|---|
| (simple) | Halt | Intraday | `status_events` | `WHERE status='H'`. Score = halt_duration × liquidity_tier |
| (simple) | Large trade | Intraday | `trades` | `notional > $1M` OR top 0.1% by notional today. Score = log(notional) |
| 4 | Sweep | Intraday | `orders_executed` | Cluster ≥ 3 distinct price levels within 10 ms window from same incoming side |
| 1 | Post-cancel cluster | Intraday | `orders_add`, `orders_delete` | Sliding-window count of (add → delete) pairs where lifetime < 50 ms, same side, concentrated in time |
| 2 | Liquidity withdrawal | Intraday | `orders_delete` | Per-symbol per-second deletion rate at top 5 levels. Compare to rolling N-min today (later swap to interday) |
| 3 | Layering | Intraday | `orders_add`, `orders_delete` | Group-add across price levels followed by group-cancel within ms |
| 5 | Iceberg | Intraday | `orders_executed` | Repeated equal-size fills at same `(symbol, price)` over sustained period |
| 6 | Time-in-book drift | Interday | all orders tables | Per-symbol-per-hour lifetime distribution diffed against baseline distribution |
| (simple) | Volume × average | Interday | `trades` | Today's per-symbol volume vs `BaselineProvider.dailyVolumeMedian()` |

Eight of nine planned scorers are intraday — meaning a fresh deploy with
zero history can ship narrations on day 1. The two interday scorers
degrade gracefully via `BaselineProvider.empty()` until the cagg
window has enough days.

### Temporal integration

`ScoreEventsActivity` runs after `RecordValidationActivity`:

```
... Parse + Validate triangle (parallel) ─▶ RecordValidation ─▶ ScoreEvents ─▶ SelectTopN ─▶ Narrate ─▶ Store
```

The activity:

1. Pre-cleans: `DELETE FROM scored_events WHERE trading_date = ?`
2. Constructs a `ScoringContext` with the date, JDBC connection, and a
   `BaselineProvider` (cagg-backed or empty depending on data availability)
3. Iterates `EventScorerRegistry.ALL`, runs each scorer, COPY-writes
   results in batches to `scored_events`
4. Returns total count of scored events written

For v1, scorers run sequentially in one activity. Output volume is
small so this is cheap (typical day: thousands of scored events across
all scorers). Can fan out to one-activity-per-scorer later if any
single scorer becomes a long pole.

### Extensibility — adding a new scorer

1. Implement `EventScorer` in `com.longexposure.scoring.scorers.MyNewScorer`
2. Add it to `EventScorerRegistry.ALL`
3. No schema change. No workflow change. No retraining.

---

## Connecting intraday and interday through the LLM

The architecture above produces `scored_events` rows. The narration step
then turns rows into prose. **The interaction between intraday and
interday data lives entirely in the `breakdown` JSON each scored event
carries** — the narrator never reaches outside that JSON.

### The grounding contract

Every claim in the rendered narrative must trace to a field in the
scored event's `breakdown` JSON. The narrator can't invent baselines
or recall historical context from training data. If the breakdown
doesn't have a number, the narration can't use it.

This pushes the design decision back to the scorer: **at score time,
the scorer decides which context goes into the breakdown.** Examples:

- `HaltScorer` produces a small breakdown: halt duration, reason code,
  symbol, liquidity tier. Self-contained. The resulting narration is
  factual recital.

  ```json
  {
    "halt_duration_s": 243,
    "halt_reason": "T1",
    "symbol": "AAPL",
    "liquidity_tier": "mega_cap"
  }
  ```

- `VolumeDeviationScorer` (interday) bakes both today's number and the
  baseline into the breakdown. The narrator sees both as facts:

  ```json
  {
    "todays_volume_shares":          287_400_000,
    "baseline_30d_median_shares":     61_200_000,
    "deviation_x":                   4.69,
    "baseline_window_days":          22,
    "baseline_window_status":        "partial"
  }
  ```

This means **the narrator doesn't care whether a scorer is intraday or
interday**. It treats the breakdown JSON as a sealed unit of facts and
styles them into prose. Same prompt template, same verifier, different
breakdown shapes.

### Per-pattern breakdown contracts

Each scorer defines its own breakdown shape, but they share a
convention: every quantitative claim possible in the narration must
appear in the breakdown. The verifier (the third activity in the
two-pass design) enforces this — every number in the prose must trace
back to a field name in `breakdown`.

For interday scorers, this includes the baseline value the scorer used
to score the event. The narrator can then phrase deviations in plain
English ("4.7× the typical day") without inventing the baseline.

### Daily-patterns narration — the cross-event synthesis

The per-event narrator is constrained: one event at a time, one
breakdown JSON, one paragraph out. But the day's output also includes a
"Daily patterns" section that observes recurring shapes across all the
day's scored events (see "Output structure" below).

This is a second LLM pass that consumes:

- The full list of scored events for the day
- Each event's breakdown JSON
- (Optional) day-level baseline context — e.g., today's total event
  count vs typical day's count, today's halt count vs typical week

The day-level context, when present, comes from a separate
`DailyAggregateBaselineProvider` query — `Optional<Long>
typicalScoredEventsPerDay()` or similar. Same degradation rule: empty
when history is missing, narrator skips comparative language.

### Why no tool-using / retrieval at narration time

We considered (and explicitly rejected) giving the narrator a
`lookup_baseline(symbol, metric)` tool. Reasons:

- **Grounding gets harder.** Tool calls happen *inside* the LLM's loop;
  the verifier can't cleanly attribute a claim to a deterministic
  source. The two-pass + verifier model rests on the breakdown being a
  finite, code-inspectable set of facts.
- **Latency.** Each tool call is a round-trip. Per-event narration is
  already 2 LLM calls; adding 1–2 tool calls per event 2–4× the per-day
  LLM time budget.
- **Hallucination surface grows.** The narrator could ask for a
  baseline that doesn't exist and then fabricate something to fill the
  gap.

The cleaner pattern is "scorer pre-fetches baselines into the
breakdown; narrator only sees breakdown." All the context the narrator
needs is decided at score time, not at narration time. Trade-off: less
flexibility, but a much stronger correctness story.

### Open design questions (still TBD)

These are intentionally not locked yet — surface them at the
narration-implementation sprint:

1. **Per-event breakdown size limits.** A breakdown that's too small
   produces thin narrations. Too big and the LLM's attention spreads
   thin. Rough cap: ~30 fields per breakdown. Adjust empirically.

2. **Cross-pattern context.** If a halt and a sweep occur in the same
   symbol within minutes, does either event's breakdown reference the
   other? Argues for a "related event ids" array in breakdown.
   Adds complexity to the verifier (a claim about another event must
   trace into THAT event's breakdown).

3. **Per-scorer narration templates.** Single universal narration prompt
   vs one-prompt-per-scorer-id. Universal is simpler but probably
   produces flatter prose. Per-scorer prompts are tighter but a
   maintenance burden. Decide once we have 100+ narrated events to look
   at.

4. **Daily-patterns vs per-event sequencing.** Run daily-patterns
   *before* per-event narration so it can inform per-event tone, or
   *after* so it can summarize? After is simpler and matches the
   "Output structure" doc — but it means per-event narrations can't say
   "as discussed in today's overall pattern...". Probably fine for v1.

5. **Interday context in per-event narration.** Right now the design
   says "interday scorers bake baselines into their breakdown". But
   intraday scorers (e.g. a halt) might *also* benefit from interday
   context ("AAPL's 5th halt this month — typical is 0.2/month"). Do
   we run an "enrich with interday context" pass on intraday scored
   events before narration, or accept that intraday events stay
   intraday-only in narration? Lean toward the latter for v1, revisit.

## Narration design principles

The narrative layer is the LLM-driven prose generation. **Expected to be the hardest design work in the project** — every other piece is byte-shuffling or schema design; this one is fuzzy-output iteration where there's no test that says "this narrative is good."

### Current working design: two-pass + verifier (subject to change)

The first concrete shape we're building toward. Three Temporal activities per event, in series:

```
ExtractFactsActivity   (LLM call #1)
   in:  score_breakdown JSON for one scored event
   out: a "narrative blueprint" JSON —
        { subject, what_happened, key_numbers[{value, label, source_field}],
          angle, tone_notes }
   The LLM enumerates the facts it'll use BEFORE writing prose, with each
   number tied to a named source field in the input.

RenderProseActivity    (LLM call #2)
   in:  the blueprint from Pass 1
   out: 2–3 sentences of prose (Bloomberg / FT register)
   The LLM can only use facts from the blueprint. Style + tone is the job,
   not fact selection. Same model, different prompt.

GroundingVerifyActivity (pure code, no LLM)
   Extracts every number/claim from the prose; asserts each appears in
   key_numbers[]; asserts each key_numbers entry's source_field traces
   to a real field in the original score_breakdown.
   Two-layer grounding: prose ⊆ blueprint, blueprint ⊆ raw scored event.
```

A fourth activity (`StoreNarrativeActivity`) persists the result keyed by `event_hash` for caching. The full per-event chain is 2 LLM calls + 1 code check + 1 DB write.

A separate **DailyPatternsActivity** does the cross-event synthesis once per trading day, consuming the full set of accepted narratives + their blueprints. Two LLM calls there too: extract themes → render the synthesis paragraph.

**Why two-pass over single-call.** Three potential hallucination paths and where each gets caught:

| Failure mode | Caught by |
|---|---|
| Pass 1 invents a fact not in `score_breakdown` | Blueprint verifier (source_field doesn't exist in input) → retry or refuse |
| Pass 2 invents a number not in the blueprint | Prose verifier (number not in key_numbers[]) → retry or refuse |
| Pass 2 misrenders a fact that IS in the blueprint | Out of scope for the verifier; relies on prompt iteration |

Retry policies live in Temporal — extract retries on invalid JSON output (max 3 tries), render retries on verifier failure (max 2 tries), neither cascades indefinitely. Events that exhaust retries are dropped, not narrated.

**This is the starting point, not the locked design.** Likely changes once we have real scored events to iterate against:
- Single-call narration as a v0 for cheap baseline comparison (does two-pass actually move the needle on hallucination rate vs single-call on our specific event shapes?)
- Per-event-type prompt templates (the halt narrative shape ≠ the sweep narrative shape; one prompt-per-pattern may produce tighter prose than one universal extract+render)
- Whether the blueprint's `key_numbers[]` is enough grounding state or we also need explicit `time_anchors[]`, `quote_anchors[]`, etc.

For 50 narrations/day × 2 LLM calls × ~150 tokens each at the joi llama.cpp throughput of ~23 tok/sec, total per-day LLM time is well under a minute. Cost isn't the constraint; quality is.

### Grounding — every claim traceable to EventScorer output

The model must understand market-microstructure vocabulary and produce interpretation grounded *entirely* in the structured data fed to it. **Nothing hallucinated from training data.** Every claim in the output text — "volume 2.3× baseline", "spread widened to 3×", "halt occurred at 14:15", "spoof-shaped cluster of 8 orders" — must be derivable from a field in the EventScorer's output for that event.

The two-pass design above is how this gets enforced operationally: Pass 1 commits to a finite set of named facts, Pass 2 can only style those, the verifier confirms the prose introduced nothing new.

### Vocabulary calibration

The model needs to know what microstructure terms mean and use them correctly:

- "Spread widened" — visible in derived top-of-book from DEEP+ book state
- "Liquidity thinned" — depth at price level decreased
- "Sweep" — multi-level execution from a single aggressive order
- "Post-cancel cluster" — pattern 1 above
- "Iceberg" — pattern 5 above
- "Spoofing-shaped" — pattern 1, *without claiming intent* (we observe the shape, not the trader's state of mind; legal nuance matters)

Tone: clear, factual, accessible to a general audience. Not "an excited blog." Not jargon-heavy. The Bloomberg / FT financial-journalist register is the target.

### Refusal on incomplete data

If a structured event has missing fields the narrative would need to reference, the model should refuse to narrate that event rather than fill in plausibly. Better one fewer narration than one wrong one.

### Caching

Each narrative cached by event hash (the event's structured input + scorer version, hashed). Re-running a day's pipeline does not re-narrate identical events. Schema already has the `narratives` table keyed on `event_hash` for this.

---

## Output structure — journalism, not a log file

The published per-day output is structured like a market column, not a raw event feed:

| Section | What it contains |
|---|---|
| **Market open summary** | What happened at and around the open — official opening prices, opening auction imbalances, notable pre-open moves carried into the open auction |
| **Morning session events** | Top-scored events from regular hours through midday (roughly 9:30am–noon ET). Halts, sweeps, sustained spread anomalies. |
| **Midday context** | Slower-paced summary of overall market tone (volume vs baseline, halt count, breadth indicators). |
| **Afternoon session events** | Top-scored events from noon to close. The close approaching tends to concentrate activity here. |
| **Close summary** | Closing auction prices, imbalance resolution, closing-cross details for IEX-listed symbols. |
| **Daily patterns** | Synthesized recurring themes across the full day. "Today saw a marked increase in post-cancel clusters across mega-cap tech…" — the *cross-event narrative* that emerges from the scored stream. |

The scorer feeds the first 5 sections (individual events, ranked). The **Daily patterns** section is its own pass — it consumes the full scored event list and asks the model to identify recurring shapes across the day. This is structurally a second LLM call with different inputs.

---

## Cross-references in the codebase

- `parser/src/main/java/com/longexposure/scoring/` — scoring package (interfaces, registry, scorers/). `EventScorer.java` is the extension-point interface.
- `parser/src/main/java/com/longexposure/temporal/activities/ScoreEventsActivity{,Impl}.java` — Temporal activity that wires the registry into the daily pipeline.
- `parser/src/main/resources/schema.sql` — `scored_events` table (this design) + `narratives` table (narration output sink, JSONB `score_breakdown` column holds the input that grounded the narration).
- `docs/temporal-design.md` — where `ScoreEventsActivity` slots into the workflow graph.
- `docs/plan.md` — sprint sequencing.
- `docs/decisions.md` — the 2026-05-11 pivot to DEEP+ is what makes order-level patterns achievable; without it most of the patterns above are out of reach.
