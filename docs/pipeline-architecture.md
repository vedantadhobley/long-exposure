# Pipeline Architecture

The canonical reference for how the Long Exposure analysis pipeline is shaped, what each stage is responsible for, and how new analytical work (statistical or otherwise) extends it without architectural churn. This document supersedes the previous "Layer 0 / Layer 1 / Layer 2 / Layer 3 / Layer 4" numbering vocabulary, which conflated several distinct concerns and broke down the moment a new stage needed to fit between existing ones.

## The pipeline

```
WIRE   ──▶ SUMMARIZE  ──▶ DETECT  ──▶ DESCRIBE  ──▶ INTERPRET  ──▶ SYNTHESIZE  ──▶ AGGREGATE
(data)    (planned)     (built)      (built)       (built)        (built)         (planned)
```

**Build status (2026-05-22):** WIRE, DETECT, DESCRIBE, INTERPRET, and SYNTHESIZE are all in production. The full LLM pipeline from parsed wire events to a single day-themes paragraph is end-to-end functional. SUMMARIZE is deferred until after the 30-day backfill (it's the stage that maintains inter-day baselines, which can't exist until there's enough history to compute them). AGGREGATE depends on SUMMARIZE + ≥30 days of SYNTHESIZE history, so it follows.

Each stage answers exactly one question. Mixing concerns across stages is the root of most of the confusion this document corrects.

### WIRE — raw atomic events on disk

What it is: 14 hypertables (`orders_add`, `orders_delete`, `orders_executed`, `trades`, `quotes`, `status_events`, etc. + derived `order_lifecycle`) populated by the parser from IEX DEEP+ pcap files. ~360 M events/day. Not code — just data.

What it answers: "What happened on the wire today, byte-for-byte?"

Read by: every downstream stage either directly or indirectly. SUMMARIZE and DETECT are the only stages that scan the full firehose; everything later reads pre-summarized output.

### SUMMARIZE — background aggregations *(planned, not built)*

What it would do: maintain per-symbol baselines and day-level aggregates that DETECT and INTERPRET join against. Things like 30-day median volume per symbol, typical median order-lifetime by symbol-tier, daily halt counts by exchange. Runs out-of-band from the daily ingest, not on the critical path.

What it answers: "What's normal for this symbol / day / sector?"

Output: a `symbol_stats` table + a `daily_stats` table, joined into DETECT's breakdown via `SymbolFields.apply()` (and a future `DayStats.apply()`).

Why it's its own stage: the alternative is computing baselines inline in DETECT, which would re-do the same work every day and tightly couple "what's normal" computation to scoring. Separating it means baselines refresh on their own cadence and DETECT becomes a pure read against an in-memory snapshot.

Status: deferred to post-launch. Inter-day scorers (`VolumeDeviationScorer`, `TimeInBookDriftScorer`) depend on it but are themselves deferred until a 30-day backfill exists.

### DETECT — the 7 scorers

What it is: Java code, deterministic, no LLM. The 7 `EventScorer` implementations (halt, large_trade, sweep, iceberg, layering, post_cancel_cluster, liquidity_withdrawal). Reads WIRE tables, applies threshold rules, emits one `selected_events` row per detected pattern with a `breakdown` JSON of measurements.

What it answers: "Did a pattern fire today, where, with what measurements?"

Output: `scored_events` (everything detected, ~660 K/day) and `selected_events` (top-N per scorer, ~90/day). Each row's `breakdown` JSON carries:

- Raw measurements (counts, durations, prices, sizes)
- **Derived analytical fields** (orders-per-level, duration-as-pct-of-session, density classifications, etc.) — added 2026-05-22 to ensure no downstream LLM stage has to do arithmetic at inference time
- Per-symbol reference data joined from `symbols` (company name, exchange, ETF flag, round lot, prev close)
- `co_occurring` block when other-scorer events nest inside this event's window

**Architectural rule: all quantitative computation belongs here or upstream (SUMMARIZE).** Downstream stages may *choose* which numbers to mention, but they never *compute* numbers. Putting arithmetic in the LLM at inference time was the leading failure mode in the breakdown-only interpretation prototype (15-20% arithmetic error rate on 21 samples). The fix is making DETECT's output rich enough that no downstream computation is needed.

Status: built. 7 scorers in production. Derived-field enrichment landed 2026-05-22 (all 7 scorers carry the analytical ratios + classifications enumerated in `interpretation-design.md`).

### DESCRIBE — per-event description prose

What it is: 2-pass LLM call (`BlueprintExtractor` → `ProseRenderer`) plus the 4-layer pure-code `GroundingVerifier`. Reads one `selected_events.breakdown`, writes 1-2 sentences of plain English to `narratives.narrative`.

What it answers: "What happened, in journalist-register prose?"

What it does NOT do: explain what the pattern means, narrate surrounding context, or compare to baselines. Strictly describes the event's own measurements.

Verifier rules (load-bearing):
1. Every number in prose ⊆ blueprint ∪ breakdown
2. Every blueprint `source_field` resolves to a real path in breakdown
3. Event's symbol appears in prose
4. Prose company name token-subset agrees with `breakdown.company_name`

Status: built. 163/164 verifier-passed on 2026-05-08 with the v8c prompt + structured-output schema.

### INTERPRET — per-event interpretation / context prose

What it is: a third LLM call per event (`InterpretEventActivity`) that takes one `selected_events.breakdown` PLUS a **surrounding wire-data window** (trades on the same symbol within ±60 sec of the event, pre-aggregated by `TradeWindow.query`) and writes 1-2 sentences of sequential / causal context.

What it answers: "What was happening around this event? What does it mean in microstructure terms?"

Two variants were prototyped (full comparison in [`interpretation-design.md`](interpretation-design.md)):

- **breakdown-only** — model reads only the breakdown + catalog. Produces analytical observations about the event in isolation. Failure mode: ~15-20% arithmetic errors at inference time. The wins (derived ratios) are all things DETECT could pre-compute — and now does, post the 2026-05-22 enrichment.
- **surrounding-wire-data** ← **SHIPPED.** Model reads the breakdown + catalog + pre-aggregated ±60-sec window of trades. Produces sequential observations like "the layering preceded a post-window volume doubling" / "the block trade was followed by another similar block 47 sec later".

Verifier rules (`InterpretationVerifier`):
1. Every numeric token in prose ⊆ breakdown ∪ pre-window summary ∪ post-window summary (with precision-rounded matching at d=0..4 in HALF_UP / FLOOR / CEILING modes to accept journalist rounding conventions)
2. Event's symbol appears literally in prose
3. Prose company name token-subset agrees with `breakdown.company_name`, with a stutter carve-out for ticker-as-common-English-word cases (`NOW`, `ETN`, etc.)

Status: built. 162/164 = **98.78%** verifier-passed on 2026-05-08 (v5 prompt after 5 iterations chasing rounding, unit-conversion, ticker-presence, and approximation patterns). Saved to `interpretations` table keyed by SHA256 hash of inputs + prompt version.

### SYNTHESIZE — daily themes

What it is: one LLM call per day (`SynthesizeDayActivity`) that reads every per-event INTERPRET output for the date plus day-level aggregates and produces a single "today's themes" paragraph (3-6 sentences). Catches cross-event coherence that no per-event view can surface: "today's pattern was open-volatility in 3x leveraged ETFs, coordinated IWM / TQQQ withdrawal at 14:00 ET, concentration of large blocks in semiconductors."

What it answers: "What's the story of the day, across all events?"

Why it's separate: per-event narration sees one breakdown at a time and can't make cross-event observations. SYNTHESIZE explicitly reads all narrations of the day as one prompt. Different sampling preset (`SamplingParams.SYNTHESIZE` — Qwen instruct-reasoning mode: temp=1.0, top_p=1.0, top_k=40, presence_penalty=2.0) because the task is reasoning across the corpus rather than rendering one event.

Prompt-content note: we ship the INTERPRET prose per event (not DESCRIBE) to keep the ~16K-token prompt within joi's `n_ctx=32768` context window. INTERPRET already restates the descriptive content and adds sequential context, so DESCRIBE is redundant for cross-event synthesis. Falls back to DESCRIBE for the rare events without an INTERPRET (~1-2% of events).

Verifier rules (`SynthesisVerifier`):
1. Every ticker-shaped token in synthesis prose must exist in the day's narrated symbol set (or be on a small allowlist of finance acronyms like NYSE, ETF, MCB, VWAP).
2. Every numeric token must appear in narration text, interpretation text, or `day_aggregates` JSON (with the same precision-rounded matching as INTERPRET, plus magnitude-tolerant rounding because synthesis is inherently aggregative).

Status: built. End-to-end on 2026-05-08: all 164 events synthesized into one paragraph; 11 tickers checked against the day's symbol set, all valid; 1 magnitude-approximation warning ("over 100,000 shares" — true at journalist precision, not verbatim in the haystack — same class as DESCRIBE's TOST punctuation glitch). Saved to `daily_synthesis` (PK = trading_date).

### AGGREGATE — weekly / monthly rollups *(planned, post-launch)*

What it would do: periodic LLM call reading multiple SYNTHESIZE outputs, producing weekly / monthly themes. Same shape as SYNTHESIZE but one zoom level higher.

What it answers: "What's the story of this week / month?"

Status: requires ≥ 30 days of SYNTHESIZE history. Blocked on SYNTHESIZE being built + 30-day backfill landing.

## Architectural rules

These are the rules that keep the pipeline coherent as it grows. Violations are how the previous architecture drifted into the "Layer 0 interpretation = sometimes raw data, sometimes new LLM pass" confusion.

### 1. Every stage answers exactly one question

If a stage's responsibility starts requiring "either / or" language, it should split into two stages. DETECT detects; it doesn't describe. DESCRIBE describes; it doesn't interpret. INTERPRET interprets; it doesn't synthesize. When the prototype tried to make DESCRIBE also do interpretive work, the prompt got muddled and quality dropped.

### 2. All quantitative computation happens in DETECT or upstream

Derived ratios (orders-per-level, percent-of-session, inter-fill-cadence) live in DETECT's breakdown enrichment, not in LLM prompts. Cross-day baselines live in SUMMARIZE, joined into the breakdown at DETECT time. LLM stages are forbidden from computing — they choose what to mention from pre-computed values and write prose.

**This is the rule that eliminates the "Variant 1 arithmetic error" failure mode by construction.** The LLM cannot make a math error if it isn't doing math.

### 3. LLM stages read pre-summarized data, never the firehose

DESCRIBE reads one breakdown row (a few dozen fields). INTERPRET reads one breakdown + a small pre-aggregated window summary. SYNTHESIZE reads ~90 narration paragraphs + day-level aggregates. AGGREGATE reads ~7-30 daily synthesis paragraphs. **No LLM call ever reads raw WIRE rows.** The summarization step is always a Java/SQL job.

### 4. Each LLM stage has its own grounding contract

DESCRIBE's contract: every number in prose ⊆ breakdown. INTERPRET's contract (when built): every number in prose ⊆ breakdown ∪ surrounding-window summary. SYNTHESIZE's contract (when built): every claim references a specific narration or aggregate. The verifier per stage is pure code; LLM-as-judge has been considered and rejected — it adds non-determinism without strengthening the guarantee.

### 5. Naming follows function, not order

Stages are `WIRE / SUMMARIZE / DETECT / DESCRIBE / INTERPRET / SYNTHESIZE / AGGREGATE` because those names describe what each stage *does*. They do not have layer numbers because numbers don't survive insertion — when INTERPRET was discovered to belong between DESCRIBE and SYNTHESIZE, the previous "Layer 2" / "Layer 3" numbering required mid-stream renumbering or fractional names ("Layer 2.5"). Function names are stable under insertion.

## Extending the pipeline — where new analysis goes

This is the architectural answer to "how do we add statistical analysis as the project grows."

### Per-event derived quantities (ratios, classifications, percentages)

→ **DETECT.** Add fields to the relevant scorer's `breakdown` JSON. Done as Java arithmetic in the scorer code. Zero LLM round-trips, zero inference-time error risk. Example: `orders_per_level`, `duration_pct_of_regular_session`, `fill_size_uniformity`.

### Cross-day baselines (volume vs typical, lifetime drift, sector context)

→ **SUMMARIZE → DETECT.** Background job maintains a `symbol_stats` / `daily_stats` table; DETECT joins those into the breakdown via `SymbolFields.apply()` (or new `DayStats.apply()`). The LLM sees the comparison as a field (`volume_vs_30d_median_x: 4.7`), never has to compute it.

### Cross-event same-day relationships (paired blocks, nested ms-events, sequenced patterns)

→ **DETECT (for deterministic nesting) + INTERPRET (for narrative observation).** The `co_occurrence enrichment` activity already handles deterministic nesting (long sec-scale event absorbs ms-scale children) at DETECT time. Looser narrative observations ("this large_trade was followed by another similar block 47 sec later") happen at INTERPRET with the surrounding-window summary as input. Don't add cross-event logic to DESCRIBE.

### Per-event interpretive / "what does this mean" prose

→ **INTERPRET.** New LLM stage, takes the breakdown + catalog + optional surrounding-window summary, writes interpretive prose. Grounded against a pure-code verifier (numbers ⊆ breakdown ∪ window summary; intent-language denylist).

### Cross-event same-day storytelling (today's themes, sector rotations)

→ **SYNTHESIZE.** Reads all the day's narrations and interpretations as one prompt. Don't try to do this at INTERPRET — INTERPRET sees one event at a time and can't observe day-level themes.

### Cross-day storytelling (this week, this month)

→ **AGGREGATE.** Reads multiple SYNTHESIZE outputs. Built after SYNTHESIZE + 30-day history exist.

### Truly novel statistical methods (Monte Carlo, factor regression, market-impact models)

→ **SUMMARIZE.** A separate background job computes the result and stores it in a table. DETECT joins the result into breakdowns. The LLM sees the result as a field, never runs the method itself. Tool-calling at LLM inference time has been explicitly rejected — it expands the hallucination surface (LLM can fabricate tool arguments) without giving meaningful access that pre-computation doesn't.

### Things that don't fit any stage

If a new piece of work doesn't fit into one of the above buckets, that's a signal to invent a new stage rather than overload an existing one. The cost of a new stage is small (one new table or activity); the cost of an overloaded stage is architectural debt that compounds over time.

## What changed from the previous naming

The previous documentation used "Layer 0 / 1 / 2 / 3 / 4" terminology that didn't survive contact with the design questions it was trying to answer. Specifically:

- **"Layer 0"** meant two different things: (a) raw wire events on disk, and (b) the new per-event interpretation stage. These are different concerns; conflating them produced multiple conversations of pure confusion.
- **The numbers carried no semantic content.** A reader couldn't tell from "Layer 2" what the stage *did*.
- **Numbers broke under insertion.** When the interpretation stage was discovered to belong between description (Layer 2) and daily synthesis (Layer 3), naming it required fractional names ("Layer 2.5") or shoehorning it into the existing numbering ("Layer 0 expansion") — both of which obscured what the stage actually does.

The function-name vocabulary in this document is the canonical replacement. Cross-references in other docs are being updated to match.

## See also

- [`interpretation-design.md`](interpretation-design.md) — full design for INTERPRET, including the breakdown-only vs surrounding-wire-data variant comparison and the catalog spec.
- [`scoring-and-narration.md`](scoring-and-narration.md) — DETECT internals, scorer catalog, the 2-pass DESCRIBE call structure.
- [`temporal-design.md`](temporal-design.md) — Temporal workflow + activity layout backing DETECT and DESCRIBE.
- [`concepts.md`](concepts.md) — accessible primer on order books and DEEP+ data; uses the old "Layer 0/1/2/3/4" framing pending a separate rewrite pass.
