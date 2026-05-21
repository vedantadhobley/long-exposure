# Scoring + Narration — Design Reference

Distilled from the project-positioning + design discussion (captured 2026-05-11). The DEEP+ pivot recorded in `docs/decisions.md` (2026-05-11 entry) makes order-level pattern detection the actual product surface. This doc is the design reference the EventScorer and LLM prompt engineering work against.

> **Status (2026-05-18):**
> - **Scoring + selection** ✅ shipped. 7 intraday scorers + `SelectTopEventsActivity`. Producing ~90 narratable events per trading day on 2026-05-08. See "Scoring architecture" below.
> - **Symbol enrichment (Phase C)** ✅ shipped. `symbols` reference table populated weekly from NASDAQ public listings + IEX SecurityDirectory. `Enrich.symbol()` joins company name, exchange, ETF flag, round lot, prev close into every breakdown.
> - **Two-pass narration** ✅ shipped. `BlueprintExtractor` → `ProseRenderer` → pure-code `GroundingVerifier` chain running against `llama-large.joi` (Qwen3.5-122B-A10B). Narrations verified for all 7 scorer types. Verifier enforces three grounding layers: (1) every number in prose ⊆ blueprint ∪ breakdown, (2) every blueprint key_number's source_field exists in breakdown, (3) every ticker-shaped token in prose must equal the event's symbol OR be a real ticker in the `symbols` reference table — no maintained denylist for non-ticker abbreviations.
> - **Cross-event linking, threshold-based selection** 🛠 next — see todo.md.
> - **Layer 3 daily synthesis, Layer-0 expansion** 📋 designed, deferred.
> - **30-day backfill + inter-day scorers** 🛑 explicitly deferred until single-day output is rock-solid.

---

## The 7 scorers in plain English

The detection vocabulary of the whole project. Every narrated event falls into one of these seven buckets. High-scoring events may get *enriched* at scoring time with deterministic summary stats about co-occurring same-symbol events of OTHER types whose intervals fall inside the parent's window — this is the "nested signals inside signals" mechanism (a liquidity_withdrawal absorbing the post_cancel/layering events that nested within its duration). See `## Scoring architecture` below.

| Scorer id | What it detects | Plain-English signal | Source data |
|---|---|---|---|
| `halt` | The stock was paused from trading | Regulator halted it for news, or a circuit breaker tripped | `status_events` (kind = 'H') |
| `large_trade` | A single trade > $1 M | A big block of stock changed hands in one print | `trades` |
| `sweep` | One aggressive order ate through multiple price levels | A buyer (or seller) hit several different prices in milliseconds — paid up to fill | `orders_executed` cluster across ≥ 3 price levels in 10 ms |
| `post_cancel_cluster` | Burst of orders placed then yanked within ms | Many short-lived orders, characteristic of certain HFT strategies (incl. spoofing-shaped activity) | `order_lifecycle` (paired add ↔ delete with lifetime < 50 ms) |
| `layering` | Same as post_cancel_cluster but spread across many price levels | Looks like manufacturing fake depth across the book — same yanking behavior at multiple prices | `order_lifecycle` (≥ 5 distinct prices in cluster) |
| `iceberg` | Hidden order revealing itself by getting filled in equal chunks at one price | A large reserve order whose displayed tip refills repeatedly | `orders_executed` runs at same `(symbol, price)` with low size variance |
| `liquidity_withdrawal` | Flood of cancels on one symbol | Market makers pulling quotes — often happens just before halts, news, or volatility events | `orders_delete` ≥ 50 cancels with < 100 ms gaps |

### How "score" works (plain English)

Each scorer assigns a numeric score to every event it detects. Higher = more interesting. But **the scores aren't directly comparable across scorers** — they're in different units:

- A halt's score is its duration in seconds (105–23 400 range)
- A large_trade's score is `log10($notional)` (6–8 range)
- A pattern scorer's score is `log10(shares) × cluster_count` (mixed units, broad range)

This is why selection (today) picks the top-N events *within each scorer* separately, then concatenates. Threshold-based selection (queued, see todo.md) replaces this with within-scorer percentile rank, putting all scorers on the same 0–1 scale.

### What gets narrated (the funnel)

Real numbers from 2026-05-08:

```
Layer 0:   ~360 M wire events on the IEX DEEP+ feed for the day
Layer 1:    ~660 K events emitted by the 7 scorers      (1 / 550)
Layer 2:        90 events selected for narration         (1 / 7 300)
Layer 3:    [not yet built] 1 daily synthesis paragraph
Layer 4:    [not yet built] 1 weekly / monthly synthesis
```

About 1 in every 4 million wire events ends up in a narrated paragraph. We are aggressively filtering signal from noise.

---

## Narration phased plan (2026-05-16)

Worked out during initial smoke tests after observing first-pass narrations. Single-pass narrations are factually grounded but reveal that the **breakdown JSON is the contract** — every quality issue is upstream of the LLM call. Four phases, each independently shippable:

| Phase | Status | Scope | Effort |
|---|---|---|---|
| **A. Strip metadata from breakdowns** | ✅ done 2026-05-16 | Dropped `score`, `trade_id`, `sale_condition_flags`, raw `ts_nanos`, wire-format `side` from breakdown JSONs. Kept in `scored_events` rows for joins/debugging, just not in the LLM-facing payload. Fixed the observed side-enum hallucination (`"side": "8"` → narrator guessed "sell" when 8 = buy). | 30 min |
| **B. Humanize values** | ✅ done 2026-05-16 | Added `duration_humanized` ("22m 17s"), `ts_et` (US/Eastern), `side_label` ("buy"/"sell"). Pre-formatted prices to 2-4 decimals where displayed. Fixed "20,044 seconds", "18,128,957 nanoseconds", and similar wire-format leaks. | 30 min |
| **C. Symbol enrichment** | ✅ done 2026-05-18 | `symbols` reference table populated from NASDAQ public listings + IEX SecurityDirectory. `Enrich.symbol()` joins `company_name`, `listing_exchange`, `is_etf`, `round_lot`, `prev_close_dollars`, `luld_tier` into every breakdown from the in-memory cache loaded at scoring-activity start. Weekly refresh via `RefreshSymbolsWorkflow`. | half-day |
| **D. RAG for daily-synthesis pass** | Deferred | Vector store of news headlines + sector commentary. Used ONLY by the day-level synthesis ("today saw heavy semiconductor activity") — NOT per-event narration. Per-event grounding semantics break with RAG (retrieved text introduces unverified claims). | sprint |

The **two-pass extract/render/verify** pipeline (currently documented in "Narration design principles") sits orthogonal to these phases and lands together with Phase A. Phases C and D are decided based on what A+B narrations look like.

## Modern LLM query patterns — decisions and rejections (2026-05-16)

The cutting-edge LLM techniques as of early 2026 and where we land on each:

| Pattern | Decision | Reason |
|---|---|---|
| **GBNF grammar-constrained decoding** | ✓ Adopt for ExtractFacts | llama.cpp natively supports grammars. Forces blueprint JSON to be structurally valid by construction — no parse-retry loops. |
| **min-p sampling + low temperature** | ✓ Adopt for prose step | min-p (post-2024) is mathematically cleaner than top-p for factual generation. Less drift at low temps. |
| **Prompt caching / KV-cache reuse** | ✓ Use automatically | llama.cpp keeps system-prompt KV cache resident across calls. We get this free by reusing the same client. |
| **Server-side batching (parallel slots)** | ✓ Adopt eventually, **capped at 2** | `llama-large.joi` is a single-GPU local model. Throughput drops sharply above 2 concurrent calls — so the parallelism win caps at ~2× (not 5-10× as a hosted endpoint could provide). Narration activities must enforce this with a concurrency limiter. See "Operational constraints" below. |
| **Pure-code rubric verifier** | ✓ Load-bearing — this is the moat | Pure-code grounding check (every number in prose ⊆ blueprint's `key_numbers[]`) is deterministic and free. The actual correctness guarantee. |
| Tool calling / function calling | ✗ Reject | Adds latency and a hallucination surface. The verifier becomes "LLM judging another LLM." We pre-fetch all needed context into the breakdown instead. |
| Self-consistency (sample-N-and-vote) | ✗ Reject | Useful for "what's the answer" tasks. Our task is "render the known answer well." Voting on prose doesn't make it more correct. |
| Chain-of-Thought / self-critique | ✗ Reject | Useful for reasoning tasks. Our reasoning is done by the scorer. |
| LLM-as-judge | ✗ Reject | Non-deterministic, costs another LLM call, weaker than pure-code rubric. |
| DSPy / prompt-as-code frameworks | ✗ Reject for now | Real value at scale (1000s of examples to optimize against). At 50-90 narrations/day we iterate by hand faster than DSPy would compile. |
| RAG (per-event) | ✗ Reject for per-event | Retrieved text introduces unverified claims; grounding semantics break. |
| RAG (daily-synthesis only) | ✓ Phase D | The daily-themes cross-event synthesis has looser grounding requirements and benefits from sector/news context. |

**The throughline**: pre-fetched deterministic context goes in the breakdown; the verifier is pure code; the LLM's job is purely "render the known facts as prose."

## Sampling parameters (2026-05-20)

Qwen3.5-122B-A10B's model card publishes recommended sampling parameters per task mode. We follow them with one deliberate deviation for extraction. Code lives in `parser/src/main/java/com/longexposure/llm/SamplingParams.java` as named presets.

| Preset | Used by | temp | top_p | top_k | min_p | presence_penalty | repetition_penalty |
|---|---|---:|---:|---:|---:|---:|---:|
| `EXTRACT` | `BlueprintExtractor` (pass 1) | 0.1 | 0.8 | 20 | 0.0 | 0.0 | 1.0 |
| `RENDER` | `ProseRenderer` (pass 2) — Qwen "instruct general" verbatim | 0.7 | 0.8 | 20 | 0.0 | 1.5 | 1.0 |
| `SYNTHESIZE` (future) | Layer 3 daily synthesis — Qwen "instruct reasoning" verbatim | 1.0 | 1.0 | 40 | 0.0 | 2.0 | 1.0 |

**Why we don't use thinking mode:** the scorer does the reasoning; the LLM only renders or synthesizes. Thinking mode would ~10× cost for no measurable quality benefit on tasks where the answer is already determined.

**`EXTRACT` departure from Qwen base:** the extractor produces strict JSON, not prose. Variety hurts here (the JSON schema is a known shape), so we pin temperature low and drop presence_penalty to zero. Other knobs match the Qwen instruct base for consistency.

**Empirical validation (2026-05-08, 164 events):** v3 (Qwen RENDER with old prompt) produced ~10% qualitative-filler narrations because presence_penalty=1.5 + temp=0.7 pushed the model to invent context to vary phrasing. v4 (Qwen RENDER + tightened prompt with adaptive length + explicit anti-filler rules) brought filler back to the pre-Qwen baseline (~0.6%). The Qwen params are usable, but only with a prompt that absorbs their incentive to introduce new vocabulary.

## Known prompt-level limitations (open)

Captured 2026-05-20, updated 2026-05-21 after the EDGAR + structured-output run. None are blockers; all are candidate work for the next prompt iteration.

### Resolved in v7 (EDGAR + structured-output)

- ~~**Ticker/word collisions when symbol enrichment is incomplete.**~~ Resolved by EDGAR overlay supplying clean canonical company names for the SEC-registered universe, and the multi-word `CompanyNameNormalizer` stripping MLP / ETN-due-date suffixes from NASDAQ Security Name when EDGAR doesn't have the ticker.
- ~~**Company-name fabrication when blueprint lacks `company_name`.**~~ Resolved by `BlueprintExtractor` pass-through: the blueprint now deterministically carries `breakdown.company_name` forward to the renderer, removing the gap that previously had the model invent names from training memory.

### Still open

1. **`render_structured.co_occurring` slot empty across 100% of narrations** (audited 2026-05-21 on the 164-event v7 run). The slot is `nullable` in the JSON schema, and the model takes that as license to always set it null and put co_occurring data into the `facts[]` array instead. End-user prose is fine — the stitched output still contains the co_occurring information, just in the wrong semantic slot — but the structured representation is less useful than designed for downstream consumers (a Layer 3 daily-synthesis pass would prefer "give me the co_occurring sentences across the day" but the slot is always empty).

   The verifier doesn't catch this because it checks numbers against the haystack, not which slot each number occupies. The data IS being narrated, just not slotted as intended.

   *Fix options when we get to it*: (a) make `co_occurring` non-nullable in the schema and require null vs sentence to be determined by whether the breakdown has the block (forcing the model to populate the slot rather than facts[]); (b) split the render call into two — separate the co_occurring sentence into its own LLM call against a tighter prompt; (c) accept the current behavior and let the stitched prose be the user-facing artifact, treating the structured slots as advisory.

2. **Residual current-state filler.** A small fraction of halt narrations append "Trading resumed following the suspension" as a 3rd sentence. Factually grounded (the halt has an end timestamp) but feels closer to filler than insight — the listener already inferred trading resumed from the end-time fact. v4 prompt's adaptive-length rule largely fixed this; v7 still produces a few. Prompt could add "do not append explanatory sentences that restate what the timestamps already convey."

3. **Number formatting inconsistency** (more pervasive than initially flagged — audited 2026-05-21). Roughly half the narrations show 4-digit integers without thousand separators: "5526 total shares", "4500 shares", "2778 total shares", "72 orders" / "7200 shares", "8260 total shares". The format depends on the extractor's `value` field — the model copies the integer verbatim from the blueprint into the prose. Fix is upstream in `Humanize` — pre-format every integer ≥ 1,000 with thousand separators before the breakdown reaches `BlueprintExtractor`, so the extractor's `value` is already "5,526" and the prose follows.

   *Why this matters for publication*: inconsistent number formatting reads less professional. A reader sees "5,526" in one narration and "5526" in the next and registers the inconsistency. Cosmetic but visible.

4. **Model case-normalization on company names.** Observed: SPYU's breakdown has "MAX S&P 500 4X Leveraged ETNs" (all-caps MAX as stored in NASDAQ); model outputs "Max S&P 500 4X Leveraged ETNs" in 2/3 narrations. The verifier's Layer-4 token-subset check passes this because comparison is case-insensitive after normalization. The model is making a paraphrase the prompt says not to make. Hard fix would require case-sensitive verifier, but that breaks the "Microsoft Corp" ↔ "Microsoft Corporation" abbreviation acceptance.

5. **Minor company-name truncations from EDGAR.** EDGAR's `title` field occasionally drops an article or word that NASDAQ retains. Observed: TTD → EDGAR "Trade Desk, Inc." vs NASDAQ "The Trade Desk, Inc."; preferEdgar picks EDGAR because it's mixed-case + clean, but loses "The". Not wrong, just slightly less brand-correct. Could be handled by a per-symbol override table (small, manual) for the few cases that matter.

6. **Run-to-run variance higher than pre-Qwen.** RENDER preset's temp=0.7 means same event re-narrated produces different sentence orderings. Verifier passes both. Not a quality regression — reproducibility is weaker than at temp=0.3. Acceptable tradeoff for journalistic prose variety; revisit if it causes operational pain.

7. **Verb-led vs subject-led prose triggers a Layer-4 false-positive.** Observed once in the 2026-05-21 run on IWM: model wrote "Liquidity withdrawal occurred on iShares Russell 2000 Index Fund (IWM), marked by 2145 deletes…" — verb-led sentence structure rather than the canonical journalist subject-led shape ("iShares Russell 2000 Index Fund (IWM) experienced a liquidity withdrawal…"). The Layer-4 regex `[A-Z]…(TICKER)` then captured the entire leading phrase "Liquidity withdrawal occurred on iShares Russell 2000 Index Fund" as the "prose company" string, which couldn't token-subset-agree with "iShares Russell 2000 Index Fund" and flagged as a mismatch. Two ways to address: (a) constrain the prompt to subject-led opening, or (b) make the Layer-4 regex smarter about extracting only the company-name-shaped substring immediately preceding `(TICKER)`. (a) is simpler; (b) is more defensive against future prose-style drift.

8. **No interpretive layer yet.** Narrations describe the *shape* of events ("8,456 shares swept across 39 executions at 11 levels in 18.1 ms") but don't explain *what that means* ("classic aggressive market-buy hitting the offer ladder, consistent with an algo crossing the spread"). That's the Layer 0 expansion + Layer 3 synthesis work, queued separately.

## Layer 0 interpretive narration (design, pre-implementation)

Per-event interpretive narration that explains what a Layer-2 description *means* in market microstructure terms. Where Layer 2 narrates the shape ("AMD experienced a layering event involving 187 orders across 116 levels in 166 ms"), Layer 0 would explain what that shape *is* in vocabulary anchored to a curated pattern catalog ("Layering describes orders posted across multiple price levels and cancelled rapidly. The same wire signature is produced by market-making, smart-order-router probes, risk-management responses to correlated moves, and — when documented as such by regulators — spoofing").

Full design in [`docs/layer-0-design.md`](layer-0-design.md). Catalog content in [`parser/src/main/resources/pattern-catalog.md`](../parser/src/main/resources/pattern-catalog.md). Architecture decision (LLM-driven vs code-driven templated) pending Day-4 prototype test in the launch sprint.

Three design principles for the catalog (rigorously enforced):

1. **No intent claims from wire data alone.** Market microstructure patterns can have many causes; documented intent requires evidence beyond the order book.
2. **Mechanism over interpretation.** Describe what is happening on the wire; don't editorialize about why.
3. **Multiple drivers, not "the" driver.** Every pattern has 2+ legitimate explanations enumerated.

The catalog also indirectly informs Layer 1 (scoring) — its "drivers" lists are the differentiation targets new scorers would aim at. Adding a one-sidedness asymmetry score helps distinguish layering drivers; a spread-anomaly scorer correlates with liquidity withdrawal's "pre-news de-risking" driver. Layer 1 detects patterns; the catalog defines what's worth differentiating *within* a pattern.

## Layer 3 daily synthesis (design, pre-implementation)

Single LLM call per day, runs after all per-event narrations are complete. Input: the day's narratives (prose + blueprint + breakdown) plus day-level metadata (date, session phases, total event count). Output: one paragraph identifying recurring themes across the day's events.

Differs structurally from per-event narration:
- **Looser grounding.** The synthesis paragraph isn't constrained to a single breakdown — it's *interpreting* the corpus of narrated events. The pure-code verifier doesn't apply here in the same way; we'd verify only that named tickers / scorer names / numerical claims that appear actually come from the day's narratives.
- **Reasoning-shaped task.** "Identify the day's themes" requires the model to find patterns across 90-164 narrations. Qwen's "Instruct mode for reasoning tasks" sampling preset fits this: `temperature=1.0, top_p=1.0, top_k=40, min_p=0.0, presence_penalty=2.0`. The increased variety + high presence_penalty discourages narrow repetition across the synthesis paragraph.
- **One per day, not one per event.** No fan-out, no concurrent-call gate to worry about. Single activity, single LLM call.

Will use the new `SamplingParams.SYNTHESIZE` preset (added when the activity is built). Codification of the Layer 3 sampling decision lives in this doc + the SamplingParams class.

## Operational constraints — `llama-large.joi`

**Hard rule: only one LLM-bearing workflow runs at a time.** The single-GPU joi node tops out at 2 concurrent decode streams (enforced JVM-wide via `LlamaClient.Semaphore(2, fair)`), and the workflow-side sliding window also caps at 2. If two LLM workflows are started concurrently, they compete for the same 2 slots — both technically "running" but only one making progress, with wasted wall-clock and likely activity timeouts. Full operational details in [`operations.md`](operations.md).



The LLM endpoint is a **local model** running on a single Strix Halo GPU (Framework Desktop, 128 GB unified memory, Qwen3.5-122B-A10B via llama.cpp/Vulkan). Observed throughput ~23 tok/sec end-of-stream. This produces specific operational rules:

- **Hard concurrency cap: 2 simultaneous chat calls.** Above 2 the per-call throughput collapses faster than the parallelism gains (the model's KV cache and compute share the GPU; more than 2 concurrent decode streams starves them all). All narration code that fans out events must enforce this — Java `Semaphore(2)`, Temporal `setMaxConcurrentActivityExecutionSize(2)` on narration activities, or both.
- **Per-event budget: ~5-10 sec** (~100-200 completion tokens at 23 tok/sec). 90 events/day × 2 calls per event (extract + render) × 5-10 sec ÷ 2 parallel slots = **8-15 min** total per-day narration wall-clock. Comfortable inside the daily pipeline.
- **No bursty schedule overlap.** If the narration step lands during another LLM-consuming workload on `joi` (e.g. a developer chatting with Open WebUI), throughput will suffer for both. The cron is at 00:00 ET when other usage is minimal — keep it there.
- **Endpoint identifier discipline.** Use the model ID as it appears in `/v1/models` (currently `Qwen3.5-122B-A10B`), not the parameter-size shorthand. The ID can change if the model is replaced; verify with `curl $LLAMA_URL/models | jq .data[].id` before relying on it.

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

How the pattern catalog above maps onto code. Four pipeline stages, each its own activity inside `ScoreWorkflow`:

```
Raw events              Scored events              Enriched events             Selected events
(hypertable rows)       (one per detection)        (top events absorb           (the narratable subset)
─────────────────       ─────────────────          co-occurring children)       ─────────────────
trades, orders_*,                ▲                          ▲                          ▲
status_events, etc.              │                          │                          │
        │                        │                          │                          │
        │     ┌───────────────┐  │   ┌──────────────────┐   │   ┌───────────────────┐  │
        ├────▶│ ScoreEvents   │──┴──▶│ EnrichWith        │───┴──▶│ SelectTopEvents   │──┘
        │     │ (7 scorers,   │      │ CoOccurrence      │       │ (percentile rank, │
        │     │  intraday)    │      │ (nested-sigs)     │       │  floor+ceiling)   │
        │     └───────────────┘      └──────────────────┘       └───────────────────┘
        │              ▲
        │              │
        │     ┌───────────────┐
        └────▶│ Interday      │   (queued; needs BaselineProvider + 30-day backfill)
              │  scorer       │
              └───────────────┘
```

Raw events live in the existing 13 hypertables and don't move. Scored
events are anything worth narrating — could be a single row (halt, large
trade) or a synthesized pattern across many rows (sweep, post-cancel
cluster). Scorers are the extension point.

**Enrichment is the new (2026-05-20) middle stage.** Replaces the earlier
"cross-event combining" attempt (see decisions.md 2026-05-20). For each
scored event whose score qualifies as "interest-floor passing", the
enrichment activity looks up other-scorer events on the same symbol whose
intervals fall *inside* the parent's `[ts, ts_end]` window. Those nested
events' summary stats get merged into the parent's `breakdown.co_occurring`
JSON block; the children get `subsumed_by_event_id = parent.event_id` so
selection skips them.

The mental model: a long sec-scale event (liquidity_withdrawal lasting
11 s) often CONTAINS a swarm of ms-scale events (28 post_cancel bursts +
6 layering bursts during those 11 s). Those ms-scale events ARE the
withdrawal at finer resolution — they're not separate stories. Enrichment
makes that nesting explicit in the parent's breakdown without forcing a
synthetic "combined" row.

What enrichment does NOT do:
- Does not merge same-scorer events on the same symbol — those are
  repetition (different question; handled in Layer 3 daily synthesis).
- Does not introduce a time-window knob — the parent's own duration
  defines the lookup window. No `MAX_GAP_MINUTES` constant.
- Does not infer intent or interpretation — that's still future Layer 0
  expansion work. Enrichment is purely deterministic counts.

### The interfaces

```java
public interface EventScorer {
    /** Stable id, e.g. "halt", "large_trade", "sweep", "post_cancel_cluster". */
    String id();

    /**
     * Scan source data for the day and emit zero or more scored events
     * via the {@code emit} callback. PUSH MODEL — the scorer must NOT
     * accumulate emitted events in a list; each call to {@code emit.accept(...)}
     * is streamed to COPY by the host activity, so memory stays bounded
     * by per-cluster state regardless of total event count.
     */
    void score(ScoringContext ctx, Consumer<ScoredEvent> emit);
}

public record ScoringContext(
        LocalDate                    tradingDate,
        Connection                   conn,
        UUID                         pipelineRunId,     // nullable
        ObjectMapper                 json,
        ScoringContext.HeartbeatCallback heartbeat      // call every N rows to keep activity alive
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
```

A `BaselineProvider` interface for the interday scorers (volume-deviation,
time-in-book drift) remains on the backlog — current scorers are all
intraday.

### Why push model

Originally the interface was `Stream<ScoredEvent> score(ctx)` with each
scorer building an in-memory `List<ScoredEvent>` and returning its
stream. That blew up on real data: PostCancelClusterScorer emitted
3.6 M clusters on a single trading day (loose thresholds — see below),
which at ~5 KB per `ScoredEvent` is ~18 GB of accumulated heap — OOM
even at 32 GB. The push model bounds memory by per-cluster state
(typically < 1 MB) regardless of how many events fire.

Implementations live in `parser/src/main/java/com/longexposure/scoring/`:

```
scoring/
  ├── EventScorer.java                   (interface — push model)
  ├── ScoredEvent.java                   (record)
  ├── ScoringContext.java                (record + HeartbeatCallback)
  ├── EventScorerRegistry.java           (List<EventScorer> ALL — 7 entries today)
  └── scorers/
      ├── HaltScorer.java                ✓ built
      ├── LargeTradeScorer.java          ✓ built
      ├── SweepScorer.java               ✓ built
      ├── PostCancelClusterScorer.java   ✓ built
      ├── LayeringScorer.java            ✓ built
      ├── IcebergScorer.java             ✓ built
      └── LiquidityWithdrawalScorer.java ✓ built
  (planned, Sprint 3+):
      ├── BaselineProvider.java          (interface)
      ├── CaggBaselineProvider.java
      ├── scorers/TimeInBookDriftScorer.java
      └── scorers/VolumeDeviationScorer.java
```

### The schema

Two tables: the full `scored_events` (everything every scorer fires)
and the per-scorer top-N `selected_events` (the narration input set).

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

CREATE TABLE selected_events (
    selected_id     BIGSERIAL    PRIMARY KEY,
    event_id        BIGINT       NOT NULL,            -- loose ref to scored_events.event_id
    trading_date    DATE         NOT NULL,
    symbol          TEXT         NOT NULL,
    ts              TIMESTAMPTZ  NOT NULL,
    ts_end          TIMESTAMPTZ,
    scorer_id       TEXT         NOT NULL,
    score           DOUBLE PRECISION NOT NULL,
    breakdown       JSONB        NOT NULL,
    source_refs     JSONB        NOT NULL,
    narration_rank  INTEGER      NOT NULL,            -- 1 = top within its scorer for the day
    selected_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX selected_events_date_scorer_rank_idx
    ON selected_events (trading_date, scorer_id, narration_rank);
```

Neither is a hypertable. `scored_events` peaked at ~660 K rows for a
busy trading day in v8 testing — small enough for standard B-tree
indexes. `selected_events` is at most ~90 rows per day under current
per-scorer caps.

Idempotency: both `ScoreEventsActivity` and `SelectTopEventsActivity`
pre-clean their target table for the trading date before inserting, so
re-runs produce a deterministic state.

### Mapping the pattern catalog to scorers — as built

| Pattern | Built | Source tables | Detection (as-built) | v8 count (2026-05-08) |
|---|---|---|---|---|
| Halt | ✓ | `status_events` | `kind='H' AND sub_type='H'`, LEAD() to find resume. Score = halt_duration_s | 105 |
| Large trade | ✓ | `trades` | `notional > $1M`. Score = log10(notional_dollars) | 149 |
| Sweep | ✓ | `orders_executed` | Cluster ≥ 3 distinct price levels within 10ms, same symbol. Score = log10(notional) × levels | 9,703 |
| Post-cancel cluster | ✓ | `order_lifecycle` | Sequential scan of materialized lifecycle, `lifetime_ns < 50ms`, cluster gap 50ms, min 20 orders. Score = log10(shares) × orders | 343,563 |
| Layering | ✓ | `order_lifecycle` | Same lifecycle scan; differs only post-fetch (require ≥ 5 distinct price levels in cluster) | 73,456 |
| Iceberg | ✓ | `orders_executed` | ≥ 8 equal-size fills (CV ≤ 0.20) at same `(symbol, price)` over ≥ 30 sec | 794 |
| Liquidity withdrawal | ✓ | `orders_delete` | ≥ 50 cancels on same symbol within 100ms gaps. Score = log10(deletes) × deletes | 233,179 |
| Time-in-book drift | ✗ Sprint 3+ | (interday) | Distribution diff vs baseline | — |
| Volume × average | ✗ Sprint 3+ | (interday) | Today's vol vs `BaselineProvider.dailyVolumeMedian()` | — |

All seven intraday scorers ship. The two interday ones wait on a
`BaselineProvider` backed by the `daily_volume_by_symbol` cagg, which
needs ≥ 30 days of history before it's useful — bootstrap backfill is
deferred to Sprint 3+.

**Pattern scorers fire a lot.** PostCancel, Layering, Iceberg-adjacent,
LiquidityWithdrawal collectively produce 100K-1M raw events per day.
That's fine because `SelectTopEventsActivity` cuts each scorer down to
its top-N before narration — see below.

### Temporal integration

```
... Parse + Validate triangle (parallel) ─▶ RecordValidation ─▶ ScoreEvents ─▶ SelectTopEvents ─▶ Narrate (TBD)
```

`ScoreEventsActivity`:
1. `SchemaManager.apply(conn)` (idempotent) then `setAutoCommit(false)`
   — critical: enables JDBC cursor-based streaming so multi-million-row
   joins don't buffer entirely into Java heap.
2. Pre-clean: `DELETE FROM scored_events WHERE trading_date = ?`
3. Background heartbeat daemon: `actx.heartbeat()` every 60s so the
   activity stays alive while long Postgres queries run with no row
   yields.
4. Iterates `EventScorerRegistry.ALL`, runs each scorer with a
   `Consumer<ScoredEvent>` that appends to a 10K-row COPY buffer and
   flushes incrementally. Per-scorer `try/catch` so one scorer failing
   doesn't kill the others.
5. Returns total rows written across all scorers.

`SelectTopEventsActivity`:
1. `DELETE FROM selected_events WHERE trading_date = ?`
2. One `INSERT INTO selected_events SELECT ... LIMIT N` per scorer_id,
   using the hardcoded `PER_SCORER_CAPS` map.
3. Returns total rows selected.

Both run sequentially in their own activities. As of 2026-05-18,
`MaterializeOrderLifecycleActivity` runs between Parse and Score; it
pairs Add ↔ Delete + last Execute into the `order_lifecycle`
hypertable once per day so PostCancel + Layering become sub-second
partial-index range scans instead of the 13 GB hash JOIN they used to
do per-scorer. `ScoreEventsActivity` itself now completes in a few
minutes for the full 7-scorer registry. `SelectTopEventsActivity` is
< 2 sec.

For ad-hoc iteration: standalone `MaterializeWorkflow`,
`ScoreWorkflow`, and `SelectWorkflow` exist so you can re-run
just the lifecycle build, scoring, or selection against an existing
dataset. `ScoreWorkflow` runs Materialize → Score → Select; the
materialize step is idempotent so it's cheap to repeat.

### Extensibility — adding a new scorer

1. Implement `EventScorer` in `com.longexposure.scoring.scorers.MyNewScorer`
2. Add it to `EventScorerRegistry.ALL`
3. Add a per-scorer cap entry in `SelectTopEventsActivityImpl.PER_SCORER_CAPS`
4. No schema change. No workflow change.

### Selection — per-scorer top-N

Narration doesn't read `scored_events` directly. The
`SelectTopEventsActivity` between scoring and narration pulls the top-N
events per scorer into `selected_events`. v1 caps:

| scorer_id | cap |
|---|---|
| halt | 20 |
| large_trade | 20 |
| sweep | 10 |
| post_cancel_cluster | 10 |
| layering | 10 |
| iceberg | 10 |
| liquidity_withdrawal | 10 |

= 90 max selected events per day. Caps are deliberately per-scorer
(not a single global threshold) because the score units aren't
comparable across scorers — halt scores are in seconds (hundreds-to-
thousands), large-trade scores are log10(notional) (6-8), pattern
scorer scores are log10(shares) × cluster_count (units mixed).
Per-scorer top-N sidesteps that entirely.

Future enhancement: score normalization for cross-scorer global
ranking, OR rank-aware narration that emits roughly one paragraph per
scorer_id and groups related events. For now the simpler per-scorer
cut is fine for the daily-column shape we want.

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
