# Launch Sprint — 2026-05-21 through 2026-06-01

10-day sprint to publication-ready. Ends Saturday 2026-05-30 with the codebase + docs + frontend + 30-day archive complete. Sunday 2026-05-31 reserved for whitepaper writing (Vedanta) and long-running historical backfill. Monday 2026-06-01 is publication day on LinkedIn + vedanta.systems + GitHub public release, also doubling as IEX-announcement day. Tuesday 2026-06-02 is IEX day 1.

This sprint runs straight into the start at IEX. The discipline is: **no bandaids**. The kind of "ship it, fix it later" tradeoff that would be acceptable on a long-running project isn't acceptable here — every shortcut becomes something we have to defend or fix while at IEX. Pace stays fast; quality stays high.

---

## End-state checklist (everything must be ✓ by Saturday night)

- [ ] All 5 issues from the 2026-05-21 audit resolved + validated by full re-narration
- [ ] Layer 3 daily synthesis shipping in the pipeline; produces a paragraph that reads cleanly across multiple test days
- [ ] Layer 0 per-event interpretation: either shipping with documented quality wins, or explicitly deferred to v2 with rationale recorded in `decisions.md`
- [ ] Code audit complete — no dead code, no maintained magic numbers in primary paths, logging uniform, error handling defensive, comments current
- [ ] All docs current: every cross-reference works, numbers consistent across docs, no stale references to retired features
- [ ] README publication-ready: opening pitch tight, screenshots embedded, install/run instructions verified by a fresh checkout
- [ ] `vedanta-systems` long-exposure-browser: surfaces today's daily synthesis at top of page, per-event drill-down, symbol filter, date picker, mobile responsive, dark-mode-correct
- [ ] 30-day backfill in flight or complete (acceptable to have it running into Sunday)
- [ ] Data retention policy implemented + documented (rolling 30-day with auto-cleanup; narratives kept indefinitely)
- [ ] End-to-end smoke testing of the full pipeline on at least 5 trading days
- [ ] LinkedIn post drafted (Vedanta authors; agent reviews for technical accuracy)
- [ ] Whitepaper drafted (Vedanta authors Sunday; agent fact-checks against docs)
- [ ] Cron schedule unpaused before Monday so Tuesday 00:00 ET ingests last Monday's data automatically

---

## Calendar

| Day | Date | Primary stream | Milestone |
|---|---|---|---|
| 1 | **Thu 5/21** (today) | Issue fixes | All 5 audit findings resolved; re-narration validates 164/164 |
| 2 | **Fri 5/22** | Layer 3 design + scaffolding | `SynthesizeDayWorkflow` interface, schema, sampling preset wired |
| 3 | **Sat 5/23** | Layer 3 implementation | First daily-synthesis paragraph end-to-end; prompt iterated against ≥3 trading days |
| 4 | **Sun 5/24** | Layer 0 design + scaffolding | Pattern catalog file + `InterpretEventActivity` skeleton |
| 5 | **Mon 5/25** | Layer 0 implementation + decision | Per-event interpretation running; quality assessed; ship-or-defer decision recorded |
| 6 | **Tue 5/26** | Code audit | Clean scan: dead code, magic numbers, logging, error handling, comments |
| 7 | **Wed 5/27** | Docs final pass + frontend audit | All docs current; cross-refs verified; frontend gaps identified |
| 8 | **Thu 5/28** | Frontend integration polish | long-exposure-browser feature-complete |
| 9 | **Fri 5/29** | 30-day backfill + smoke testing | Backfill in flight; multi-day end-to-end testing complete |
| 10 | **Sat 5/30** | Final QA + publication prep | Last QA pass, screenshots, LinkedIn draft, GitHub public-readiness |
| — | **Sun 5/31** | Whitepaper (Vedanta) + monitoring | Long backfills continue. Agent on standby for fact-checks. |
| — | **Mon 6/1** | **Publish.** IEX announcement on LinkedIn. | Live. |
| — | **Tue 6/2** | Vedanta day 1 at IEX | Pipeline runs autonomously via cron. |

---

## Day-by-day breakdown

### Day 1 — Issue fixes (Thursday 5/21, today)

Five issues from the audit. None are bandaids; each has a clean structural fix.

| # | Issue | Fix | Est |
|---|---|---|---|
| 1 | Number formatting (~half narrations missing commas on 4-digit ints) | `Humanize.formatInteger()` — pre-format every integer ≥1000 with thousand separators before the breakdown reaches `BlueprintExtractor`. Apply at the scorer level so the breakdown's `value` field is already comma-separated. | 1h |
| 2 | `render_structured.co_occurring` slot empty in 100% of narrations | Make `co_occurring` non-nullable in the JSON schema when the breakdown has a co_occurring block. Branch the schema: include `null` in the type only when the breakdown lacks the block. Forces the model to populate the slot rather than dump the data into `facts[]`. | 1.5h |
| 3 | "Trading resumed" filler tail on 3/6 halts | Prompt tightening — explicit rule "do not append explanatory sentences that restate what timestamps already convey." Principle-based, no example. | 30m |
| 4 | Layer-4 false-positive on verb-led IWM prose | Regex tightening — capture only the company-name-shaped substring immediately preceding `(TICKER)`. Anchor on a word boundary or sentence start. | 1h |
| 5 | CORZ "/tx" suffix from NASDAQ | Add `\b[A-Z][a-z]?\/[a-z]{2,3}\b$` pattern to `CompanyNameNormalizer`'s multi-word pre-strip list. | 30m |

Then a full re-narration to validate. Total: ~5 hours active work + 40-min re-run.

### Day 2 — Layer 3 scaffolding (Friday 5/22)

- **Schema**: new `daily_synthesis` table — `(trading_date PK, model_id, synthesis_prose TEXT, themes JSONB, narrative_count INT, model_id TEXT, created_at)`.
- **`SamplingParams.SYNTHESIZE`** — Qwen "Instruct mode for reasoning tasks" verbatim (`temp=1.0, top_p=1.0, top_k=40, presence_penalty=2.0`).
- **`DaySynthesizer` class** — owns the prompt, takes `(date, list<narration + structured + event_type + symbol>)`, emits `{themes[], synthesis_prose}` via structured-output JSON schema.
- **`SynthesizeDayActivity`** — Temporal activity reading from `narratives`, calling DaySynthesizer, writing `daily_synthesis`. Idempotent (UPSERT on trading_date).
- **`SynthesizeDayWorkflow`** — ad-hoc replay entry point following the same pattern as `NarrateWorkflow` / `ScoreWorkflow`.
- **Wiring** — add as Phase 6 in `DailyPipelineWorkflow` after Cleanup. Dependency: Narrate completion. No co-occurrence issues to worry about; one workflow, one LLM call per day.

### Day 3 — Layer 3 implementation (Saturday 5/23)

- Implement DaySynthesizer with iterative prompt refinement against real data (2026-05-08 + the backfilled days as they land).
- Prompt design — themes structure: 3-5 themes per day, each with: theme description, supporting symbols, scorer types involved. Synthesis prose: 3-5 sentences identifying recurring patterns.
- Verifier for Layer 3 — looser than Layer 2 (no per-number grounding) but every symbol mentioned must appear in at least one of that day's Layer-2 narrations. Every event_type referenced must be one of the 7 scorer IDs.
- End-to-end test: kick `SynthesizeDayWorkflow` on 2026-05-08, read output, iterate on prompt until prose is publishable-quality. Repeat on 2-3 other days as they're available.

### Day 4 — Layer 0 architecture test (Sunday 5/24)

**Catalog already drafted** at `parser/src/main/resources/pattern-catalog.md` (committed 2026-05-21). Full design discussion in [`docs/layer-0-design.md`](layer-0-design.md). The architecture decision (LLM-driven vs code-driven templated) is pending an empirical test.

Day-4 work:

1. **Build LLM-driven Layer 0 prototype** (Option A from layer-0-design.md):
   - `InterpretEventActivity` — per-selected-event Temporal activity called after Narrate completes.
   - Reads the Layer-2 narration + breakdown + the catalog entry for the event's scorer.
   - Custom sampling preset `SamplingParams.INTERPRET` (similar to RENDER, possibly lower temperature for tighter catalog adherence).
   - Output: one interpretation sentence.
2. **Verifier Layer-5**:
   - Numbers in interpretation must trace to breakdown.
   - Significant tokens in interpretation must be subset of catalog entry's significant tokens (enforces vocabulary discipline).
3. **Schema migration**: add `narratives.interpretation TEXT` column.
4. **Run on 2026-05-08** for empirical assessment.

### Day 5 — Decision A vs B + production wiring (Monday 5/25)

Read the prototype output cold against the same 164 events' Layer-2 narrations. Decision criteria (per layer-0-design.md):

- Are the interpretations adding insight that the Layer-2 narration doesn't? If yes, A wins.
- Does the model stay within catalog vocabulary, or does it drift?
- Do the per-event contextualizations read better than what code-substituted templates would produce?

**If A wins**: ship the LLM-driven layer as-is. Decision recorded in `decisions.md`. Frontend (Day 8) surfaces interpretation as a separate visual block.

**If B wins**: extend the catalog with `Templated interpretation` fields per scorer (templates with `{placeholder}` substitution), refactor `InterpretEventActivity` to do code-side substitution rather than LLM call. The verifier framework already in place still validates the templated output. Decision recorded in `decisions.md`.

**If toss-up**: default to B (code-driven). The catalog content is the value; the LLM is incremental. Lower risk + simpler beats marginal naturalness.

The discipline: better to ship tight Layer 2 + Layer 3 + a defensible Layer 0 than a half-baked LLM-driven Layer 0 with edge-case drift.

**How Layer 0 enhances Layer 1**: Layer 0 doesn't change scoring at runtime, but the catalog is a *design spec* for future scoring work — its "drivers" lists are the differentiation targets new scorers would aim at (e.g., one-sidedness asymmetry could help distinguish layering drivers; spread-anomaly detection would correlate with liquidity withdrawal's "pre-news de-risking" driver). Full reasoning in [`docs/layer-0-design.md`](layer-0-design.md) under "How Layer 0 relates to Layer 1".

### Day 6 — Code audit (Tuesday 5/26)

Systematic codebase pass:

1. **Dead code** — files unreferenced, methods private + unused. Delete.
2. **Magic numbers** — Tier 2 from `todo.md` (12 scorer thresholds + selection budget). Decide each: move to `scorer_config` table if tunable, or document rationale if code-constant is right.
3. **Logging uniformity** — every activity logs `activity_name + event_id + elapsed_ms + outcome` consistently. Every LLM call logs `prompt_tokens + completion_tokens + concurrent_in_flight`.
4. **Error handling** — every Temporal activity has explicit retry policy. Every JDBC connection in try-with-resources. No silent failures.
5. **Comment quality** — comments referencing retired features (combine experiment) cleaned up. No `// TODO` markers without a corresponding `todo.md` entry.
6. **Test coverage gaps** — anything load-bearing without tests. Especially the narration pipeline (extractor, renderer, verifier, all the v7 changes).
7. **Naming consistency** — `Tops` / `Deep` / `Dpls` enforced across the codebase. No stragglers using "DeepPlus" or similar.
8. **Dockerfile / compose** — review for production-readiness. Memory limits explicit. No accidentally-exposed ports.

### Day 7 — Docs final pass + frontend audit (Wednesday 5/27)

- **README** — polish for first-impression. Opening pitch tight. Screenshots embedded.
- **Every doc file** — final currency pass: numbers match, cross-refs work, retired features not referenced.
- **decisions.md** — completeness check. Every architectural choice made during this sprint recorded with context/alternatives/tradeoffs.
- **Frontend audit** — open `~/workspace/dev/vedanta-systems`, check the long-exposure-browser component current state. List concrete gaps for Day 8. Verify the Express API routes match the schema we ship.

### Day 8 — Frontend integration polish (Thursday 5/28)

In `~/workspace/dev/vedanta-systems`:

- **Daily synthesis at top of page** — when Layer 3 has run, the synthesis paragraph is the page header.
- **Per-event drill-down** — each event card expands to show: prose, blueprint JSON, breakdown JSON, verifier_passed flag, interpretation (if Layer 0 shipped).
- **Filters** — symbol filter, scorer-type filter, date range picker.
- **Mobile responsive** — works on phone-width displays.
- **Dark mode** — default + correct contrast.
- **Symbol search / autocomplete** — feeds from `symbols` reference table.

### Day 9 — Backfill + smoke testing (Friday 5/29)

- **Pick 30 trading days**: 2026-04-21 through 2026-05-21 (most recent month).
- **Background-run the chain** for each: download → parse → validate → score → enrich → select → narrate → synthesize-day. Serialize the heavy steps (parse alone is ~35 min/day; full chain ~1.5 hr/day; 45 hours of compute for 30 days serial). Backfill runs through Saturday into Sunday.
- **Cleanup observability** — verify `RetentionSweepActivity` runs correctly: drops `*.pcap.gz` files after successful pipeline, drops Postgres chunks older than 30 days via `drop_chunks()`.
- **Smoke testing** — pick 3-5 random days from the backfill, read every narration, confirm no fabrications / no filler regression / Layer 3 readable.

### Day 10 — Final QA + publication prep (Saturday 5/30)

- **Last full QA pass** — read all docs end to end, all narrations from 3 random backfill days.
- **Screenshots** — daily-synthesis page, event drill-down, mobile view — for LinkedIn + README.
- **LinkedIn post draft** (Vedanta authors; agent reviews):
  - Opening: announcement of starting at IEX + the project being open-sourced.
  - Brief technical pitch: free reference IEX DEEP+ parser + AI narration pipeline.
  - Link to vedanta.systems for live demo, link to GitHub for repo.
- **GitHub public-readiness** — LICENSE file present, .gitignore correct, no secrets in repo, README is the front door.
- **Unpause cron schedule** — `daily-pipeline-cron` set to active state so Tuesday 00:00 ET begins ingesting Monday's data.
- **Mark sprint complete** — update this doc with a 🎉 status row.

### Sunday 5/31 — Whitepaper + monitoring

- **Vedanta**: writes the whitepaper using the now-phenomenal docs as source material. Agent on standby for technical fact-checks.
- **Agent**: monitors backfill progress, fixes any failures, no new feature work.

### Monday 6/1 — Publication

- LinkedIn post goes live in the morning.
- vedanta.systems live with daily archive + today's narration (Monday's session = Tuesday's parse).
- GitHub repo public.
- IEX announcement.

### Tuesday 6/2 onward — Pipeline runs autonomously

- Cron fires at 00:00 ET Tue-Sat.
- Each fire: download → parse → validate → score → narrate → synthesize → cleanup.
- Retention policy keeps the rolling 30-day window automatic.
- Vedanta starts at IEX.

---

## Data retention strategy

Documented separately because it's load-bearing for sustainable operations:

| Data | Retention | Mechanism | Notes |
|---|---|---|---|
| `.pcap.gz` files in `/storage/raw/` | Deleted after successful pipeline | `CleanupFilesActivity` runs at end of each daily run | Already implemented; verify in Day 9 audit |
| Wire-format hypertables (13 tables) | Rolling 30 days | `RetentionSweepActivity` via TimescaleDB `drop_chunks('table', INTERVAL '30 days')` | Already implemented; expand if Layer 0 needs longer history |
| `order_lifecycle` hypertable | Rolling 30 days | Same `drop_chunks` mechanism | |
| `scored_events` | Rolling 30 days | Add `DELETE WHERE trading_date < CURRENT_DATE - 30` to `RetentionSweepActivity` | **New work in Day 9** |
| `selected_events` | Rolling 30 days | Same | **New work in Day 9** |
| `narratives` | **Kept indefinitely** | No retention | Small table (~90-164 rows/day; ~30 KB/day; 11 MB/year). Historical narratives are the product. |
| `daily_synthesis` | **Kept indefinitely** | No retention | One row per trading day. Tiny. The day-level archive. |
| `symbols` | Refreshed weekly | `RefreshSymbolsWorkflow` upserts | Static reference; no rows expire |
| `validation_runs` | Rolling 90 days | Add to `RetentionSweepActivity` | **New work in Day 9** |
| `pipeline_runs` | Rolling 90 days | Add to `RetentionSweepActivity` | **New work in Day 9** |

The principle: **raw + intermediate data has a TTL, narrated outputs are forever.** A reader visiting vedanta.systems can browse every narrated day from launch onward indefinitely. The intermediate wire-format data lives just long enough to re-narrate if needed.

---

## Acceptance criteria (Saturday night sign-off)

The 10 items in the end-state checklist at the top of this doc. Each is a binary ✓/✗ on Saturday night. Anything ✗ delays Sunday's whitepaper work.

---

## Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Layer 0 doesn't yield quality wins | Medium | Low | Documented as v2 in `decisions.md`; ship without it. The user explicitly accepted this outcome. |
| Frontend integration is more work than 1 day | Medium | High | Day 7 audit identifies scope early. If Day 8 isn't enough, Layer 0 gets deferred to make room. |
| Backfill doesn't finish by Saturday | High | Low | Acceptable to have it running into Sunday. Publication on Monday doesn't require all 30 days complete — even 7-10 days of archive is enough for launch. |
| Pipeline failure during backfill | Medium | Medium | Each day is independent; one failure doesn't block others. Manual re-run via `ScoreWorkflow` / `NarrateWorkflow`. |
| Llama-large.joi goes down during backfill | Low | Medium | Already saw this during this session; recovery is documented. Backfill resumes from where it left off. |
| Hidden bug surfaces in Day 6 code audit that needs deep refactor | Medium | High | Day 7-8 has buffer for fixes. If a deep refactor is needed, Layer 0 gets deferred. |
| Documentation gap discovered Monday during whitepaper writing | Low | Low | Sunday has agent on standby for fact-checks; ad-hoc doc updates are fine. |

---

## Notes

- This sprint runs concurrently with the existing dev cycle. The cron schedule stays paused until Saturday so we don't accidentally ingest data into an in-flight migration.
- **Hard rule: never run two LLM-bearing workflows concurrently.** The joi single-GPU caps at 2 concurrent decode streams; the per-workflow semaphore already saturates it. Two simultaneous LLM workflows compete for the same permits with no throughput gain and likely activity timeouts. Full rule in [`operations.md`](operations.md) under "Operational rule — never run two LLM-bearing workflows concurrently". Applies to NarrateWorkflow, future SynthesizeDayWorkflow, and Layer-0 interpret activity.
- **No shortcuts.** The discipline that got us to a 99.4% verifier-pass rate is the same discipline that gets this to production-ready.
- The whitepaper is Vedanta's piece, drafted Sunday from the now-phenomenal docs. Agent fact-checks but does not author.
- Monday 6/1 doubles as: project launch + IEX announcement + last day off before starting work.
