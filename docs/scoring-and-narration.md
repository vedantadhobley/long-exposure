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

- `parser/src/main/java/com/longexposure/scoring/EventScorer.java` — currently a stub. Each pattern above gets its own scorer class implementing a shared interface.
- `parser/src/main/resources/schema.sql` — `narratives` table is the output sink. JSONB `score_breakdown` column holds the structured input that the narration was grounded in.
- `docs/plan.md` — sprint sequencing (DEEP+ parser first, then this work).
- `docs/decisions.md` — the 2026-05-11 pivot to DEEP+ is what makes order-level patterns actually achievable; without it most of the patterns above are out of reach.
