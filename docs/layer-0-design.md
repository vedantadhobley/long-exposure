# Layer 0 — Interpretive Narration Design

> Status: design draft, 2026-05-21. Architecture decision pending empirical test of LLM-driven prototype on Day 4 of the launch sprint.

## What Layer 0 is

The interpretive layer that sits **above** Layer 2 (per-event prose description) and explains what the described event *means* in market microstructure terms. Where Layer 2 describes the shape ("AMD experienced a layering event involving 187 orders across 116 price levels in 166 ms"), Layer 0 explains what that shape *is* in terms of trading-mechanism vocabulary, with explicit boundaries around what we will and will not infer from wire data alone.

## The catalog

The source-of-truth for Layer 0 interpretations is [`parser/src/main/resources/pattern-catalog.md`](../parser/src/main/resources/pattern-catalog.md). Per-scorer entries enumerate:

- **mechanism** — what the pattern IS at the wire level (factual)
- **drivers** — what legitimate market activities can produce the pattern
- **inference limit** — what we explicitly DO NOT claim from wire data
- **canonical interpretation** — the single safe sentence Layer 0 may produce
- **sources** — published references where available

Three design principles govern catalog content:

1. **No intent claims from wire data alone.** Market microstructure patterns can have many causes; documented intent (e.g., spoofing) requires evidence beyond the order book.
2. **Mechanism over interpretation.** Describe what is happening on the wire; do not editorialize about why.
3. **Multiple drivers, not "the" driver.** Every pattern has 2+ legitimate explanations enumerated.

## Three implementation options

The catalog content is settled; the implementation strategy is not. Three reasonable shapes:

### Option A — LLM-driven Layer 0

Per-event activity (`InterpretEventActivity`) called after Narrate completes. Reads the Layer-2 narration + breakdown + catalog entry for the event's scorer, makes one LLM call (preset `SamplingParams.SYNTHESIZE` or a custom `INTERPRET` preset), emits an interpretation sentence. Verified by a Layer-5 pure-code check: significant tokens in the interpretation must be a subset of the catalog entry's significant tokens.

- **Pro**: natural prose that can contextualize the specific event (e.g., "This 4-hour halt on ODTX…")
- **Pro**: every event gets a uniquely-phrased interpretation
- **Pro**: more interesting research-wise; demonstrates the grounding architecture at one more layer
- **Con**: model can drift from catalog vocabulary even with verifier
- **Con**: 1 additional LLM call per event (~164 calls/day at current scale; ~30 min/day of GPU)
- **Con**: another verification surface to maintain

### Option B — Code-driven templated Layer 0

Catalog has both `canonical_interpretation` (generic) and `templated_interpretation` (with placeholders like `{symbol}`, `{duration}`, `{shares}`). Code substitutes from the breakdown at narration time. No LLM call for Layer 0.

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

- Are the interpretations adding insight that the descriptive Layer-2 narration doesn't? If yes, A wins.
- Does the model stay within catalog vocabulary, or does it drift? If it drifts, A's risk is too high — go to B.
- Do the per-event contextualizations read better than what code-substituted templates would produce? If yes, A wins.
- Are there any failures the Layer-5 verifier catches that look like quality issues vs. paraphrase noise? If quality issues, A's risk is too high.

The bar for A is "clearly better than B, not just different." Default to B if it's a toss-up.

## How Layer 0 relates to Layer 1 (scoring)

Layer 1 is detection; Layer 0 is interpretation. They sit at different layers of the architecture and don't directly couple at runtime. **But the catalog has indirect, design-time value for Layer 1:**

1. **Catalog "drivers" lists are the differentiation targets for scoring.** If layering has 5 legitimate drivers, a more sophisticated layering scorer would add features that help downstream consumers differentiate them — e.g., one-sidedness ratio, post-cancel asymmetry, time-of-day weighting. Today's scorers detect the pattern; tomorrow's could classify by likely driver. The catalog is the spec for what classification would mean.

2. **Catalog "inference limit" tells us what scoring CANNOT establish from wire data alone.** That bounds where scoring work has diminishing returns. We will never have a scorer that detects "spoofing"; we will always have one that detects "rapid post-cancel patterns." The catalog enforces that distinction.

3. **Catalog informs new scorer ideas.** Patterns mentioned in the catalog's drivers but not currently scored are candidates for new scorers. Example: the "spread widening associated with liquidity withdrawal" driver suggests a `spread_anomaly` scorer would add value. Such a scorer would feed both Layer 1 detection AND Layer 0 interpretation by populating co_occurring context.

So the catalog isn't a Layer 1 enhancement at runtime, but it's a Layer 1 *roadmap*. New scorers, new breakdown fields, new co_occurring relationships — all guided by what the catalog enumerates.

## Verifier design (Layer-5)

For both A and B, the published interpretation must pass a Layer-5 pure-code check:

1. **Numbers in interpretation ⊆ blueprint ∪ breakdown** (same rule as Layer-2 Layer-2)
2. **Significant tokens in interpretation ⊆ catalog entry tokens** (new — enforces interpretation stays within catalog vocabulary)
3. **No intent-bearing phrases** (catalog entries are designed not to contain them; this check is a backstop)

For Option B, checks 1+2 are trivially satisfied because code did the substitution. The verifier exists to catch any code-side templating bugs.

For Option A, check 2 is load-bearing — it's how we catch the LLM drifting beyond catalog vocabulary.

## Schema implications

`narratives` table gets one new column:

```sql
ALTER TABLE narratives ADD COLUMN IF NOT EXISTS interpretation TEXT;
```

Populated by the Layer-0 step (Option A's activity or Option B's code) when a catalog entry exists for the event's scorer. Null when not yet implemented or when the Layer-5 verifier rejects the output.

API surfaces it on `/event/:id` and includes it in the `/day/:date` per-event payload. Frontend renders it as a separate visual block from the Layer-2 prose ("WHAT HAPPENED" prose card + "WHAT IT MEANS" interpretation card).

## Open questions

- **Per-event vs per-scorer interpretations.** Option B currently assumes one interpretation per scorer (substituted with event facts). Should certain events get DIFFERENT interpretations based on data shape? E.g., a 4-hour halt vs a 5-minute halt — same scorer, but the durations imply different things. Could be handled by multiple templates per scorer.
- **Layer-0 narration when Layer-2 prose already contains interpretation hints.** Some Layer-2 narrations naturally weave in some interpretation context ("an iceberg execution") — does Layer 0 then add value or just repeat?
- **Catalog versioning + audit trail.** When catalog entries change, do we re-narrate past events with the new interpretations, or freeze them as-of-publication? Lean toward freezing.

## References

- Pattern catalog: [`parser/src/main/resources/pattern-catalog.md`](../parser/src/main/resources/pattern-catalog.md)
- Decisions log: [`docs/decisions.md`](decisions.md) (2026-05-21 entries for catalog approach when finalized)
- Sprint plan: [`docs/launch-sprint.md`](launch-sprint.md) Day 4-5
