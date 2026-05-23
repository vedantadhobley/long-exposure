# INTERPRET — Design and Implementation

> Status: **shipped 2026-05-22.** 162/164 = **98.78%** verifier-passed on 2026-05-08 (v5 prompt, after 5 iterations chasing rounding / unit-conversion / ticker-presence / approximation failure modes). Variant 2 (surrounding-wire-data) + Option A (LLM-driven) is the shipped architecture. Empirical comparison of the two prototype variants is retained below as a record of why Variant 2 won.

This document describes the **INTERPRET** stage of the analysis pipeline — a per-event LLM pass that produces interpretive / contextual prose downstream of DETECT (the scorers) and DESCRIBE (per-event description). For the canonical pipeline-stage vocabulary and how stages relate to one another, see [`pipeline-architecture.md`](pipeline-architecture.md).

This doc was previously titled "Layer 0 — Interpretive Narration Design" — the old name conflated the new interpretation pass with raw wire data (which `concepts.md` calls "Layer 0"). The new function-name vocabulary in `pipeline-architecture.md` replaces the layer-numbering scheme. References to "DESCRIBE narration" in older docs refer to DESCRIBE; references to "Layer-N verifier" refer to the four-tier grounding check inside the verifier, not pipeline stages.

## What got built

- `parser/src/main/java/com/longexposure/temporal/activities/InterpretEventActivity{,Impl}.java` — per-event activity (load event, query ±60-sec windows, build prompt, LLM call, verify, upsert).
- `parser/src/main/java/com/longexposure/temporal/workflows/InterpretWorkflow{,Impl}.java` — workflow with 2-in-flight sliding-window dispatch. Called as a child of `DailyPipelineWorkflow` (after `NarrateWorkflow`, before `SynthesizeDayWorkflow`); also runnable standalone for ad-hoc replay.
- `parser/src/main/java/com/longexposure/narration/InterpretationVerifier.java` — pure-code grounding check. Numbers ⊆ breakdown ∪ pre-window ∪ post-window summaries (with HALF_UP / FLOOR / CEILING precision-rounded matching). Symbol presence. Company-name agreement with stutter carve-out.
- `parser/src/main/java/com/longexposure/narration/TradeWindow.java` — shared helper for pre/post trade-window aggregation. Pre-computes `price_range_dollars` + `total_notional_million_dollars` so the model doesn't compute units at inference time.
- `interpretations` table — keyed by SHA256 hash of inputs + prompt version + model id (independent caching from DESCRIBE narratives).

## Prompt-iteration history (preserved for future readers)

| Version | Pass rate | Key change |
|---|---|---|
| v1 | 0% | Baseline. VWAP precision mismatch (haystack `19.6883` vs prose `$19.69`). |
| v2 | 40% | Removed window-size leakage ("60-second windows" was in prompt + examples). |
| v3 | 81% | Required ticker in prose; pre-computed `price_range_dollars`; verifier accepts integer rounding for haystack ≥10. |
| v4 | 93% | FLOOR + CEILING for d=0 (LLM rounds 146.5 → 146, not 147); explicit no-unit-conversion rule. |
| v5 | 98.78% | `notional_million_dollars` in TradeWindow; FLOOR at d=1..4 (LLM truncates 72.7957 → 72.79); Layer-4 stutter carve-out (NOW/ETN-as-English-word); explicit no-approximation rule. |

## Two variants prototyped — empirical comparison (2026-05-22)

Two smoke-test variants of INTERPRET have been run end-to-end on the 2026-05-08 dataset. They differ in *what data the LLM reads*, not in implementation strategy. The downstream A-vs-B implementation choice (LLM vs template) sits underneath whichever variant we pick.

### Variant 1 — breakdown-only INTERPRET

LLM input: scorer id + IEX catalog entry (mechanism + canonical interpretation) + DESCRIBE prose + `breakdown` JSON for the event.

LLM prompt: "produce one analytical observation about THIS event — compute derived ratios, cross-reference co_occurring, note pattern intensity."

Question being answered: *what does this pattern type mean, applied to this specific event's own measurements?*

Best output (AMD layering):
> "The 187 orders distributed across 116 distinct price levels yield a sparse density of roughly 1.6 orders per level, while the 34,100-share total implies an average notional exposure of $14,520 per level within the $2.09 price range."

Failure modes observed (21-sample run):
- **Arithmetic errors ~15–20 %.** Example: MOBI halt — "5h 34m represents a 20% duration relative to the full 6.5-hour regular trading session" (actually 86 %, model contradicted itself in the same sentence). FLEX — "139.8 shares per round lot of 100" (nonsense).
- **Fabrication when data was absent.** MOBI halt reason code in the breakdown is the string `"NA"`; model emitted "(LULD or news pending)" — invented a plausible alternative.
- **Intent slippage.** "Suggests a consolidated institutional transfer rather than fragmented retail accumulation" (FLEX).

Diagnosis: this variant asks the LLM to do arithmetic at inference time (`field_a / field_b = derived ratio`), which is a known weakness of autoregressive LLMs at any scale. Most of the "wins" are derivations the DETECT scorer could pre-compute and add to the breakdown anyway.

### Variant 2 — surrounding-wire-data INTERPRET

LLM input: same as Variant 1, **plus** a pre-aggregated summary of trades on the same symbol in the ±60-second windows around the event (trade count, total shares, total notional, VWAP, price range, first/last timestamp).

LLM prompt: "identify the sequential / causal context the surrounding data reveals."

Question being answered: *what was happening in the market around this event?*

Best outputs (21-sample run):
- **FLEX large_trade**: "The $10.6M Flex Ltd. block trade on NASDAQ was immediately followed by a post-window surge where 7 trades totaling 76,520 shares executed at a VWAP of $139.7561, nearly matching the event's volume and price." — *the model identified a paired block trade in the post-window*.
- **AMD layering**: "concluded at 09:41:26.241, immediately preceding a post-event window where trade volume nearly doubled to 12,533 shares at a VWAP of $430.70" — *real sequential observation, pre 7,256 → post 12,533*.
- **INTC liquidity_withdrawal**: "six-fold increase in trade volume and a sharp price expansion to $119.37 compared to the pre-event window's 70 trades and $117.12 VWAP" — *real cross-window comparison*.
- **OTAI= halt**: "the ±60-second windows on either side recorded zero trades and no notional volume. The event occurred in total isolation" — *correctly handled the empty-window case as a valid observation*.

Failure modes observed (21-sample run):
- **Zero arithmetic errors.** Because the task is read+compare, not divide.
- **Mild causal speculation** (~4–5 / 21): "suggests cautious re-entry", "may have catalyzed institutional follow-through". Prompt-tractable.
- **One category error**: VTWO iceberg referred to as "algorithmic sweep". Single instance.

Diagnosis: this variant gives the LLM information that *isn't already in the breakdown* — surrounding trades — so it produces narrative the breakdown alone could not. The error rate dropped because we shifted the task from compute-arithmetic to read-and-compare-numbers, which LLMs are good at.

### Side-by-side

| | Variant 1 (breakdown-only) | Variant 2 (surrounding-context) |
|---|---|---|
| New data passed to LLM | None beyond breakdown + catalog | ±60-sec trade window from `trades` table |
| Question answered | "What does this pattern mean?" | "What was happening around this event?" |
| Best example | "1.6 orders per level" | "block trade followed by another similar block" |
| Failure modes (in 21 samples) | ~3–4 arithmetic errors / fabrications | 0 arithmetic errors; ~4–5 mild causal speculations |
| New code required | None | SQL query + Java aggregation |
| Per-event cost | 1 LLM call | 1 LLM call + 2 small SQL queries |
| Does it add narrative the description doesn't already imply? | Marginally — most output is implicit in breakdown | **Yes — surrounding-context narrative cannot be derived from the breakdown alone.** |

### What this means for the implementation-strategy options below

Variant 2 is what the *original* concepts.md description meant ("LLM call that reads the DESCRIBE narration + a slice of surrounding wire data… '…the layering came right before an 8,000-share market buy at the same price'"). Variant 1 is what the prototype defaulted to before this empirical work — a smaller scope I drifted to without flagging.

Variant 2 makes the **Option B (templated)** path materially weaker:

- Variant 1 + Option B is straightforward: substitute breakdown fields into a per-scorer template.
- Variant 2 + Option B would require the template engine to also substitute *surrounding-window data*, conditionally branch on whether pre/post windows have activity, and choose between "isolation" / "preceded by" / "followed by" / "flanked by" framings. That's halfway to a small DSL.

Variant 2 + Option A (LLM-driven) handles all those conditional structures naturally — it just reads the window summary and writes prose. The "Option B is safe and boring" advantage doesn't carry over cleanly to Variant 2.

**Updated recommendation:** Variant 2 (surrounding-context) + Option A (LLM-driven) + extended grounding-verifier (every numeric token in interpretation must appear in either breakdown OR surrounding-context summary; reuses DESCRIBE's grounding-check class with one additional haystack source).

## On "emergent classification" — what an LLM can and cannot find here

The broader question raised was whether the LLM can *discover* emergent patterns the scorers don't already detect. Honest answer: **yes, but only at the layer that has the right view, and only for things shaped like narrative observation rather than detection**.

### What the LLM CAN find that DETECT scorers can't

These all showed up in Variant 2's 21-sample run:

- **Paired block trades** (FLEX, ETN) — a large_trade followed by another similar-sized block in the next 60 seconds. DETECT scores each block independently; the *pairing* is a property of the trading day, not the events.
- **Pre/post regime shifts** (AMD layering: trade volume nearly doubled after layering ended; INTC withdrawal: 6× post-event volume + price expansion). DETECT detects the layering; the *consequence* lives in the surrounding wire data.
- **Isolation as a finding** (OTAI= halt: zero trades in either window). DETECT can't emit "this event happened in isolation" — that's a fact about the absence of *other* events, which requires querying for what's NOT there.
- **Post-halt market behavior** (MOBI: 200-share cautious re-entry vs ODTX: 1,219-share burst). Same scorer fired both halts; the *shape of resumption* is post-halt context.

These are all "emergent" in the sense that they're not in the DETECT scorer catalog and would require new scorers to detect deterministically. The LLM finds them because surrounding-wire-data summary is in its prompt.

### What the LLM CAN'T find, even with more data

- **Quantitative patterns requiring computation.** "AAPL's volume is 4.7× its 30-day median" requires arithmetic over historical data — error-prone at inference, belongs in DETECT.
- **Strict detection thresholds.** "≥ 50 cancels in <100 ms gaps" — fuzzy boundaries lead to inconsistent recall. DETECT with explicit rules is the right tool.
- **Cross-symbol coherence** (today, with current data scope). "TQQQ + SQQQ + IWM all withdrew liquidity at 14:00 ET" would require feeding cross-symbol context, which the current ±60-sec window doesn't carry. Could be added; would benefit SYNTHESIZE (daily synthesis) more than INTERPRET (per-event).
- **Genuine novelty.** The LLM can identify *instances* of patterns whose vocabulary is in the catalog. It can't reliably invent new pattern categories. New microstructure categories come from human reading + literature, not the model.

### Architectural takeaway

The amount of emergent observation we get is bounded by *what data we put in the prompt*. The model isn't going to find what isn't there. Three knobs:

1. **DETECT enrichment** — pre-compute more derived fields in scorer code (`orders_per_level`, `inter_fill_cadence_sec`, etc.) so the breakdown carries more arithmetic per event. Eliminates Variant 1's failure surface.
2. **Surrounding-context window content** — currently trades only. Could add quote/spread snapshots, surrounding scored events on the same symbol, halt status. Each additional source extends what INTERPRET can narrate.
3. **SYNTHESIZE daily synthesis** — entirely separate stage. Reads all 90 per-event narrations + interpretations as one prompt, finds day-level themes that no per-event view can surface.

The most narrative-rich pipeline has *all three* dials turned up:

- DETECT produces breakdowns rich with derived ratios (no LLM arithmetic risk)
- DESCRIBE describes each event in prose (already shipped)
- INTERPRET interprets each event with surrounding-wire-data context (Variant 2 — what we just tested)
- SYNTHESIZE synthesizes the day's events into themes (planned, not built)

## What this document originally said (still valid for implementation strategy)

The Options A / B / C framing below was written when only Variant 1 had been considered. The empirical work above shifts the recommendation toward Variant 2 + Option A. The implementation-strategy discussion (LLM vs templated; verifier design; schema; per-event vs per-scorer) is still correct in its own terms.

---

## What INTERPRET is

The interpretive layer that sits **above** DESCRIBE and explains what the described event *means* in market microstructure terms. Where DESCRIBE describes the shape ("AMD experienced a layering event involving 187 orders across 116 price levels in 166 ms"), INTERPRET explains what that shape *is* in terms of trading-mechanism vocabulary, with explicit boundaries around what we will and will not infer from wire data alone.

## The catalog

The source-of-truth for INTERPRETs is [`parser/src/main/resources/pattern-catalog.md`](../parser/src/main/resources/pattern-catalog.md). Per-scorer entries enumerate:

- **mechanism** — what the pattern IS at the wire level (factual)
- **drivers** — what legitimate market activities can produce the pattern
- **inference limit** — what we explicitly DO NOT claim from wire data
- **canonical interpretation** — the single safe sentence INTERPRET may produce
- **sources** — published references where available

Three design principles govern catalog content:

1. **No intent claims from wire data alone.** Market microstructure patterns can have many causes; documented intent (e.g., spoofing) requires evidence beyond the order book.
2. **Mechanism over interpretation.** Describe what is happening on the wire; do not editorialize about why.
3. **Multiple drivers, not "the" driver.** Every pattern has 2+ legitimate explanations enumerated.

## Three implementation options

The catalog content is settled; the implementation strategy is not. Three reasonable shapes:

### Option A — LLM-driven INTERPRET

Per-event activity (`InterpretEventActivity`) called after Narrate completes. Reads the DESCRIBE narration + breakdown + catalog entry for the event's scorer, makes one LLM call (preset `SamplingParams.SYNTHESIZE` or a custom `INTERPRET` preset), emits an interpretation sentence. Verified by a pure-code grounding-verifier check: significant tokens in the interpretation must be a subset of the catalog entry's significant tokens.

- **Pro**: natural prose that can contextualize the specific event (e.g., "This 4-hour halt on ODTX…")
- **Pro**: every event gets a uniquely-phrased interpretation
- **Pro**: more interesting research-wise; demonstrates the grounding architecture at one more layer
- **Con**: model can drift from catalog vocabulary even with verifier
- **Con**: 1 additional LLM call per event (~164 calls/day at current scale; ~30 min/day of GPU)
- **Con**: another verification surface to maintain

### Option B — Code-driven templated INTERPRET

Catalog has both `canonical_interpretation` (generic) and `templated_interpretation` (with placeholders like `{symbol}`, `{duration}`, `{shares}`). Code substitutes from the breakdown at narration time. No LLM call for INTERPRET.

- **Pro**: 100% defensible — every interpretation came directly from a file you reviewed
- **Pro**: faster (no extra LLM call)
- **Pro**: trivially iterable — edit catalog, next narration reflects the change
- **Pro**: the "did the model phrase this for a general audience" concern goes away entirely
- **Con**: less natural per-event variation; every iceberg gets a similar interpretation tail
- **Con**: less interesting architecturally

### Option C — Hybrid

Code injects the templated interpretation, then a light LLM pass smooths it into natural prose flow within the larger narration. Composability between catalog content and prose flow.

- **Pro**: natural flow with strong grounding
- **Con**: complexity without commensurate quality gain over B
- **Con**: still has the model-drift risk of A

## The decision strategy

Per agreement on 2026-05-21: **build Option A as a prototype on Day 4, read the output, then decide A-vs-B based on observed quality.**

The catalog content is the load-bearing part regardless of which option ships. Both A and B consume the same catalog file. Switching between A and B post-decision is a few hours of work; the catalog and the surrounding architecture (Temporal activity layout, schema, verifier framework) carry over either way.

### What "observed quality" means

After running the Option A prototype on 2026-05-08:

- Are the interpretations adding insight that the descriptive DESCRIBE narration doesn't? If yes, A wins.
- Does the model stay within catalog vocabulary, or does it drift? If it drifts, A's risk is too high — go to B.
- Do the per-event contextualizations read better than what code-substituted templates would produce? If yes, A wins.
- Are there any failures the grounding-verifier catches that look like quality issues vs. paraphrase noise? If quality issues, A's risk is too high.

The bar for A is "clearly better than B, not just different." Default to B if it's a toss-up.

## How INTERPRET relates to DETECT

DETECT is detection; INTERPRET is interpretation. They sit at different layers of the architecture and don't directly couple at runtime. **But the catalog has indirect, design-time value for DETECT:**

1. **Catalog "drivers" lists are the differentiation targets for scoring.** If layering has 5 legitimate drivers, a more sophisticated layering scorer would add features that help downstream consumers differentiate them — e.g., one-sidedness ratio, post-cancel asymmetry, time-of-day weighting. Today's scorers detect the pattern; tomorrow's could classify by likely driver. The catalog is the spec for what classification would mean.

2. **Catalog "inference limit" tells us what scoring CANNOT establish from wire data alone.** That bounds where scoring work has diminishing returns. We will never have a scorer that detects "spoofing"; we will always have one that detects "rapid post-cancel patterns." The catalog enforces that distinction.

3. **Catalog informs new scorer ideas.** Patterns mentioned in the catalog's drivers but not currently scored are candidates for new scorers. Example: the "spread widening associated with liquidity withdrawal" driver suggests a `spread_anomaly` scorer would add value. Such a scorer would feed both DETECT detection AND INTERPRET by populating co_occurring context.

So the catalog isn't a DETECT enhancement at runtime, but it's a DETECT *roadmap*. New scorers, new breakdown fields, new co_occurring relationships — all guided by what the catalog enumerates.

## Verifier design (grounding-verifier)

For both A and B, the published interpretation must pass a pure-code grounding-verifier check:

1. **Numbers in interpretation ⊆ blueprint ∪ breakdown** (same rule as DESCRIBE)
2. **Significant tokens in interpretation ⊆ catalog entry tokens** (new — enforces interpretation stays within catalog vocabulary)
3. **No intent-bearing phrases** (catalog entries are designed not to contain them; this check is a backstop)

For Option B, checks 1+2 are trivially satisfied because code did the substitution. The verifier exists to catch any code-side templating bugs.

For Option A, check 2 is load-bearing — it's how we catch the LLM drifting beyond catalog vocabulary.

## Schema implications

`narratives` table gets one new column:

```sql
ALTER TABLE narratives ADD COLUMN IF NOT EXISTS interpretation TEXT;
```

Populated by the INTERPRET step (Option A's activity or Option B's code) when a catalog entry exists for the event's scorer. Null when not yet implemented or when the grounding-verifier rejects the output.

API surfaces it on `/event/:id` and includes it in the `/day/:date` per-event payload. Frontend renders it as a separate visual block from the DESCRIBE prose ("WHAT HAPPENED" prose card + "WHAT IT MEANS" interpretation card).

## Open questions

- **Per-event vs per-scorer interpretations.** Option B currently assumes one interpretation per scorer (substituted with event facts). Should certain events get DIFFERENT interpretations based on data shape? E.g., a 4-hour halt vs a 5-minute halt — same scorer, but the durations imply different things. Could be handled by multiple templates per scorer.
- **INTERPRET narration when DESCRIBE prose already contains interpretation hints.** Some DESCRIBE narrations naturally weave in some interpretation context ("an iceberg execution") — does INTERPRET then add value or just repeat?
- **Catalog versioning + audit trail.** When catalog entries change, do we re-narrate past events with the new interpretations, or freeze them as-of-publication? Lean toward freezing.

## First step — smoke-test path to LLM output (fastest decision input)

The 3-4h production scaffolding path isn't needed to *decide* A vs B. For decision purposes we need only one thing: read actual LLM-driven interpretation output cold and judge whether it's noticeably better than what code-driven templates would produce.

Smoke-test path (~45 min from start to first LLM output):

1. New file: `parser/src/main/java/com/longexposure/narration/InterpretSmokeTest.java`
   - Reads N narratives from `narratives` (sampled across all 7 scorer types)
   - For each, loads the catalog entry for the scorer
   - Constructs an interpretation prompt
   - Calls `LlamaClient.chat(...)` with sampling params
   - Prints results to stdout
2. Triggered via `IEX_INTERPRET_SMOKE=<date>` env var, mirroring the existing `IEX_REVERIFY` pattern in `Main.java`
3. Output: 14-21 interpretations (2-3 per scorer type) for read-cold review

Promotion to production happens only after A vs B is decided. If A wins, 2-3 more hours wires `InterpretEventActivity` + `InterpretWorkflow` + `GroundingVerifier` extensions + schema migration. If B wins, the smoke test is throwaway and we write the templated substituter (~1h).

## Cross-layer benefit map

How INTERPRET benefits (or affects) each other layer:

| Layer | Direct benefit | Indirect benefit |
|---|---|---|
| **INTERPRET itself** | The artifact — per-event interpretation prose | — |
| **DETECT** | None at runtime | Catalog "drivers" lists are the design-time spec for future scorer fields (one-sidedness ratio, spread anomaly, etc.). DETECT is unchanged for launch. |
| **DESCRIBE** | None | Catalog vocabulary may eventually influence DESCRIBE's prompt for consistent terminology |
| **SYNTHESIZE** | **Major.** SYNTHESIZE reads per-event interpretations as input. Synthesis with interpretation context is qualitatively richer than synthesis with description-only. ("Today saw heavy layering with patterns consistent with rapid quote adjustment" only emerges when INTERPRET has classified mechanism per event.) | — |
| **AGGREGATE** | Same as SYNTHESIZE — weekly/monthly synthesis benefits from per-event interpretation. | — |

**DETECT changes required**: None at runtime. The catalog informs FUTURE DETECT work (new scorers, new breakdown fields) as a design roadmap, not a runtime dependency.

## Concrete walkthrough — what each option produces

### Option A (LLM-driven) on a VTWO iceberg

Inputs to the LLM call:
- DESCRIBE narration: *"VTWO saw an iceberg execution of 12,600 shares at $114.84 over 22m 17s..."*
- Breakdown JSON (full)
- Catalog entry for `iceberg` (mechanism + drivers + inference limit + canonical interpretation)

Plausible LLM output:
> *"VTWO's 22-minute execution with 126 round-lot fills is consistent with the iceberg pattern — a small displayed tip refilling automatically from a hidden reserve, typical of institutional execution seeking minimal market impact."*

The LLM contextualized the catalog content with this specific event's facts (22 minutes, 126 fills, round-lot tip size).

### Option B (code-driven templated) on the same event

Catalog entry adds a `templated_interpretation` field with `{placeholder}` substitutions:

```
"This {duration} iceberg execution of {total_shares} shares on {company_name} ({symbol})
is consistent with the pattern of displayed tips refilling automatically from a hidden
reserve. Common drivers include institutional execution seeking minimal market impact,
algorithmic VWAP strategies, and market-making activity."
```

Code substitutes from breakdown. Result:
> *"This 22m 17s iceberg execution of 12,600 shares on Vanguard Russell 2000 ETF (VTWO) is consistent with the pattern of displayed tips refilling automatically from a hidden reserve. Common drivers include institutional execution seeking minimal market impact, algorithmic VWAP strategies, and market-making activity."*

Every iceberg event gets nearly-identical interpretation prose with different facts substituted.

## What Option A uniquely provides

| Capability | A | B |
|---|---|---|
| Natural prose flow integrating event context | ✓ | Limited to template substitution |
| **Selective driver emphasis** based on data shape (e.g., a 166ms layering → emphasize "rapid quote adjustment"; a 2.6-second one → emphasize "smart-order-router probes") | ✓ | Would require multiple per-shape templates (catalog complexity) |
| **Conditional inclusion of co_occurring context** ("preceded by liquidity withdrawal — consistent with pre-news de-risking") | ✓ | Would require complex conditional logic |
| Voice consistency with DESCRIBE prose | ✓ | Template prose feels distinct from LLM-generated DESCRIBE |
| Run-to-run variation for repeat-viewing | ✓ | Identical text every time |

The two intellectually-meaningful items in this list are **selective driver emphasis** and **conditional co_occurring inclusion**. The other three are cosmetic.

## Decision criteria — when to go to Option B

We choose B if any of:

- **Quality not noticeably better than templated.** Smoke-test output reads similar to "if we just substituted variables into the canonical_interpretation, this would be roughly as good"
- **Vocabulary drift.** LLM uses words not in the catalog entry > ~5% of the time (verifier-fail rate)
- **Genericity.** Every event of the same scorer gets nearly-identical interpretation prose regardless of data shape (LLM isn't using the contextualizing power)
- **Run-to-run inconsistency.** Same event interpreted twice produces meaningfully different outputs

If none of those is true, A wins.

## What we'd lose with Option B (and how to mitigate)

- **Some interpretive depth on enriched events.** A halt preceded by liquidity withdrawal can't easily get "consistent with pre-news de-risking" via template substitution — that requires noticing the co_occurring context. *Mitigation*: add per-data-shape template variants in the catalog (e.g., `templated_interpretation_with_co_occurring`).
- **Voice consistency.** DESCRIBE prose is LLM-generated; Option B interpretations would be more uniform. *Mitigation*: write templates in journalist register, identical to DESCRIBE's voice.
- **Run-to-run variation.** Same event gets the same text every time. *Mitigation*: arguably a feature (reproducibility) not a bug.

### What we'd gain downstream with Option B

- **SYNTHESIZE sees stable input.** Synthesis is easier when per-event interpretation is predictable
- **Audit is trivial.** Every word in every interpretation came from a file you reviewed
- **Iteration is fast.** Catalog edit → next narration reflects it immediately, no LLM re-run needed

## References

- Pattern catalog: [`parser/src/main/resources/pattern-catalog.md`](../parser/src/main/resources/pattern-catalog.md)
- Decisions log: [`docs/decisions.md`](decisions.md) (2026-05-21 entries for catalog approach when finalized)
- Sprint plan: [`docs/launch-sprint.md`](launch-sprint.md) Day 4-5 (INTERPRET re-ordered ahead of SYNTHESIZE — 2026-05-21 decision)
