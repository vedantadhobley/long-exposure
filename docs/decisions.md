# Decisions Log

Append-only record of architectural and operational decisions, ordered by date. When a non-obvious choice gets made, append a dated entry here with: what we decided, what we considered, why we picked the chosen path, and what still leaves open.

---

## 2026-05-11 (late) — Code identifiers use DPLS (filename token), docs/prose use DEEP+ (product name)

**Context.** Two names for the same feed: the spec / product name is **DEEP+**, the HIST filename token is **DPLS**. We had been using "DeepPlus" in Java code (package `com.longexposure.deepplus`, classes `DeepPlusMessage*`, enum `Feed.DEEPPLUS`, variable `deepPlusFile`), which was asymmetric with the 4-letter `Tops` / `Deep` siblings and grated to read.

**Decided.** Rename all code-internal references to use **`Dpls`** / **`DPLS`** / **`dpls`** (matching the HIST filename token). User-facing prose (README marketing line, narrative copy) keeps **DEEP+** since that's the canonical product name IEX uses and it has stronger SEO/recognition (people search for "DEEP+ parser"). Docs reference DEEP+ as the product, with a one-line equivalence note at the top of `AGENTS.md` and `docs/protocol-notes.md`.

**What changed in code.**
- Package: `com.longexposure.deepplus` → `com.longexposure.dpls` (12 files)
- Sealed marker: `DeepPlusMessage` → `DplsMessage`
- Router: `DeepPlusMessageRouter` → `DplsMessageRouter`
- Validators: `DeepPlusBboCrossValidator` → `DplsBboCrossValidator`, `DeepVsDeepPlusValidator` → `DeepVsDplsValidator`
- Test: `DeepPlusMessagesTest` → `DplsMessagesTest`
- Enum constant: `Feed.DEEPPLUS` → `Feed.DPLS`; display label `"DEEP+"` → `"DPLS"`
- All identifier-cased forms (`deepPlusFile`, `onlyDeepPlus`, etc.) → `dpls*`
- All javadoc references `DEEP+` → `DPLS` inside code

**What stayed DEEP+.**
- README marketing line: "the first open-source IEX DEEP+ parser in any language" — the product name people will search for.
- README "Data feed selection" section: introduces DEEP+ as the product, with the DPLS-is-the-filename-token explanation alongside.
- Historical entries in this log: prior decision entries keep their original phrasing.

**Why this matters.** Naming consistency in code is load-bearing for readability — `Tops`, `Deep`, `Dpls` reads as three siblings; `Tops`, `Deep`, `DeepPlus` reads as two-and-an-outlier. The product name in marketing copy is also load-bearing for discoverability — "DEEP+" has years of IEX documentation behind it; "DPLS" does not. Splitting code-vs-prose lets each optimize for its own constraint.

---

## 2026-05-11 — Pivot: DEEP+ is the v1 product; TOPS becomes the validation oracle

**Supersedes the 2026-05-10 "TOPS v1 / DEEP+ phase 2" decision below.** The reasoning in that entry still applies in isolation, but a load-bearing assumption — "each feed is 2–3 weeks of work" — was invalidated by today's execution pace.

**What changed.** On 2026-05-11 we compressed Days 1–10 of the README plan into roughly one working day:
- pcap-ng + libpcap reader (~270 LOC, no native deps)
- IEX-TP transport decoder
- All 7 admin message decoders + sealed `AdminMessage` interface
- All 5 TOPS-specific trading decoders + `TopsMessageRouter`
- 48 passing unit tests
- Postgres + TimescaleDB schema with 8 hypertables + continuous aggregate
- COPY-based `TimescaleWriter`
- End-to-end run: 9.5 GB 2026-05-08 HIST → 294,790,405 messages written in 22:27 min

The morning's "phase 2 = 2–3 week post-launch sprint" was already an estimate, not a measurement. Given actual velocity, the DEEP+ parser is a 1–2 session sprint — comparable to one day's TOPS work. **Treating TOPS as a separate v1 launch with DEEP+ deferred no longer makes sense.**

**Decided.**

- **v1 = DEEP+** (DPLS feed). All scoring, narration, UI work targets DEEP+ event types from day 1.
- **TOPS code stays.** It's repurposed as a **validation oracle**: same trading day, parse both feeds, derive TOPS-equivalent BBO from DEEP+ book state, diff against the actual TOPS feed. Where they disagree, the DEEP+ decoder has a bug.
- **The 7.75M trades + 285M quotes already loaded in Postgres are not thrown away** — they become the cross-check reference data for DEEP+ decoder correctness.
- **Positioning shifts** from "reference implementation of the IEX TOPS parser in Java" to **"first open-source reference implementation of IEX DEEP+ in any language."** Verified 2026-05-11: no public DEEP+ parser exists in any language (GitHub search results below). This is a genuine community contribution.

**Why the velocity argument is load-bearing.**

The morning's 7 reasons for TOPS-first reduce to two structural concerns when velocity is fast:

1. **Validation difficulty for DEEP+** — book-state reconstruction has no analogous "compare to a published number" check.
2. **No reference parser exists** for DEEP+ in any language.

Both are real but **both are mitigatable**, and the mitigation is exactly the TOPS work we already did:
- The same trading day's TOPS feed serves as the reference. Derive top-of-book from the DEEP+ order-book state machine and compare to the canonical TOPS Quote Update / Trade Report stream. Per-symbol per-second BBO must match. Per-symbol total trade size + count must match.
- We already have the TOPS infrastructure (parser + writer + 285M loaded quotes for 2026-05-08). Reusing it as a validation oracle is essentially free.

The five other morning-of reasons (stepping-stone reuse, ship risk, LLM prompt iteration time, spec maturity, reputation narrative) either no longer apply at our pace or now favor the pivot:
- Stepping-stone reuse: confirmed today. ~60% of parser LOC is in the shared `admin/` + `wire/` + `transport/` + `pcap/` packages and works unchanged for DEEP+.
- Ship risk: lower at our pace than feared.
- LLM iteration: we want the scorer designed against DEEP+ event types from day 1 (richer narrative surface) rather than re-design after a TOPS-only v1 launch.
- Spec maturity: DEEP+ 1.02 spec is excellent and we've read it cover-to-cover.
- Reputation: "first open-source DEEP+ parser, with a public narrative product on top" is strictly better than "shipped a generic TOPS-only narrative, then added DEEP+."

**GitHub search verification (2026-05-11).** Queries `IEX DEEP+ parser`, `IEX DPLS`, `iex order book parser` — all return either zero results or only TOPS/DEEP parsers from years ago. The 5 existing parsers (`WojciechZankowski/iextrading4j-hist` Java, `rob-blackbourn/iex_parser` Python, three C++ DEEP parsers, `B1tWhys/iextool` Python) all predate the Jan 2025 DEEP+ spec publication and don't support its trading-message set.

**What this changes in the codebase / docs.**

- `docs/plan.md` — phase 2 section reframed as "next sprints"; Day 11+ moves to DEEP+ implementation.
- `README.md` — "reference implementation of the IEX TOPS parser" → "first open-source IEX DEEP+ parser." TOPS demoted to "validation oracle." Feed-selection section reframed.
- `AGENTS.md` — "touching feed-handling code" guardrail flipped (DEEP+ is v1; TOPS is the validator).
- `docs/todo.md` — start-of-next-session checklist now leads with "download a DPLS .pcap.gz."
- Source code layout (`com.longexposure.tops.*` for TOPS, future `com.longexposure.dpls.*` for DEEP+) is unchanged — same per-feed package convention, just different priority order.

**Open risks (unchanged from morning analysis, accepted).**

- No reference parser to cross-check against. Mitigated by the TOPS cross-validation above.
- DEEP+ history only back to Jan 2025 (~16 months). Fine for narrating yesterday + 30-day baselines; rules out multi-year analysis.
- Book-state validation is structurally harder than trades-totals validation. We'll likely need a SNAP-equivalent reconstruction comparison as a tertiary check eventually.

**What's still open.** When this entry was written we hadn't yet downloaded a DPLS file. First action next session: pull the 2026-05-08 DPLS HIST file (same date as the TOPS data already loaded) so cross-validation is a same-day same-symbols comparison.

---

## 2026-05-10 — TOPS for v1, DEEP+ (not DEEP) for phase 2; SNAP feeds out of scope

**Context.** The README originally specified TOPS-only for v1 with DEEP earmarked for phase 2, citing file sizes (TOPS "few hundred MB", DEEP "13 TB across full history"). Pulling real numbers from the HIST API for recent dates showed all three feeds (TOPS, DEEP, DEEP+/DPLS) are within 5% of each other in compressed size — ~7 GB/day in 2025, ~2.9 GB/day in 2024. The original size-based argument for TOPS doesn't hold; the choice has to rest on parser complexity, validation difficulty, and ship risk instead.

After pulling all 7 spec PDFs from `~/workspace/data/long-exposure/specs/` and reading the trading-message sections of TOPS 1.66, DEEP 1.08, and DEEP+ 1.02:

**Decided.**

- **v1: TOPS 1.6 only.** Single feed, single parser path, validate against IEX's published daily totals.
- **Phase 2: DEEP+ (skip DEEP entirely).** Order-by-order book, every individual displayed order tracked through its Add/Modify/Delete/Execute lifecycle.
- **SNAP feeds: permanently out of scope.** TOPS SNAP / DEEP SNAP / DEEP+ SNAP are request-response TCP services for live consumers joining mid-day; they don't appear in HIST (which is what we read). Not relevant to a T+1 pipeline.
- **Phase 2 is not a multi-month delay.** Target it as a 2–3 week follow-up sprint after v1 ships — day ~22 ships TOPS publicly, day ~40 ships DEEP+ alongside it.

**Why TOPS for v1 (and not jumping straight to DEEP+).**

1. *Validation difficulty differs qualitatively.* TOPS validates trivially: sum trade volumes per symbol, compare to IEX's daily totals. A decoder bug fails loudly. DEEP+ validation requires book-state reconstruction at sampled timestamps and per-order lifecycle correctness — and there is no analogous "compare to a published authoritative number" check. A subtly broken DEEP+ decoder silently produces wrong book state, and narratives confidently lie. This is the scariest bug class in market data parsing.

2. *TOPS is a stepping stone, not throwaway.* ~60% of the parser code (transport, admin decoders, framing, gap handling, Postgres writer skeleton, Temporal pipeline, LLM narration loop, Caddy/deploy/UI wiring) is shared across all three feeds. v1 = build the shared layer + TOPS trading decoders. Phase 2 = add DEEP+ trading decoders + order book state machine + order-narrative templates. No throwaway work.

3. *Ship risk.* TOPS-at-day-22 gives a near-certain shippable product. DEEP+-by-day-22 carries meaningful probability of "half-shipped" or "didn't ship." For a public, reputation-attached project, variance dominates expected value.

4. *LLM prompt iteration needs working parsed events.* Days 18–19 are prompt tuning. If the parser isn't producing real events by then, prompts don't get tuned. TOPS-running-by-day-7 leaves weeks for prompt iteration.

5. *Spec maturity.* TOPS 1.66 dated Oct 2021, stable, multiple reference implementations exist (open-source IEX parsers in Python/Go/Rust). DEEP+ 1.02 dated Jan 2025; thin ecosystem, fewer references to cross-check decoders against.

6. *Reputation narrative.* "Shipped v1 in 22 days, shipped order-by-order v2 three weeks later" reads as judgment + execution. "Attempted DEEP+ and got 80% of the way" reads as ambition without execution. The former is a strictly better story even if total code written is similar.

**Why DEEP+ (not DEEP) for phase 2.**

Information-theoretic: **DEEP+ ⊃ DEEP ⊃ TOPS**. DEEP+ carries every individual displayed order's lifecycle (Add/Modify/Delete/Execute by Order ID). Aggregating order sizes by `(symbol, side, price)` derives DEEP-equivalent price levels for free. So DEEP is a stopping point we'd throw away — once we've invested in book reconstruction, the marginal cost to track individual orders is small.

Narrative value: DEEP+ unlocks order-lifecycle stories ("8 orders posted and cancelled within 50ms — classic spoof shape"; "median order time-in-book on SPY collapsed from 800ms to 90ms") that map directly to IEX's transparency brand. DEEP only unlocks depth-of-book narratives, which are less distinctive.

**Why SNAP feeds are out of scope.**

SNAP (TOPS/DEEP/DEEP+) is a TCP request-response service used by live consumers who joined the multicast mid-day and need to recover the current order book state. Read of `deep-plus-snap-1.03.pdf` confirms: SnapshotRequest → SnapshotStart/SnapshotData(...)/SnapshotEnd response carrying the latest admin messages + Add Orders needed to rebuild book state at a given Sequence Number. Auth-gated, 1000-requests/day quota, credentials via Market Ops.

Long Exposure consumes complete daily .pcap.gz files from HIST T+1. Every message is in correct sequence in the file; mid-day recovery isn't a concept that applies. The SNAP specs stay archived at `~/workspace/data/long-exposure/specs/` for completeness but won't be implemented.

**What this changes in the codebase.**

- README's "Alternatives considered" → DEEP section becomes a DEEP+ section; size claims corrected to ~7 GB/day per feed (was "few hundred MB" / "13 TB").
- `docs/plan.md` Day 22 phase 2 note → DEEP+, with the 2–3 week follow-up framing.
- `docs/protocol-notes.md` already updated with real spec content (TOPS/DEEP/DEEP+ trading message tables).
- Architecture work that needs to be DEEP+-ready from day 1:
  - `DownloadHistActivity(date, feed_name, version)` — parameterized
  - `events` hypertable has a `feed_source TEXT` column from initial schema
  - Parser package structure: shared `transport/` + `admin/` + per-feed `tops/` (later `dpls/`)

**Confidence + what's still open.**

High confidence on the v1 = TOPS decision. The "skip DEEP, go straight to DEEP+ in phase 2" call is opinionated and could be revisited if any of these turn out differently than expected:

- If TOPS daily-totals validation reveals decoder bugs that take longer than ~1 week to chase, phase 2 may slip; DEEP+'s harder validation surface is a bigger version of the same problem.
- If real `*.pcap.gz` files exhibit framing edge cases (truncated packets, gap-fill artifacts) we haven't anticipated, the shared transport layer might absorb more time than planned and the per-feed work could compress.
- If, after seeing real TOPS narratives at launch, the depth-of-book signal feels worth the extra integration cost, we could land DEEP between v1 and DEEP+ as an intermediate milestone — but this is unlikely given the strict information-superset argument.

**Addendum 2026-05-10 (later) — DPLS = DEEP+ access confirmed, history depth caveat, reference implementation availability.**

After publishing the decision above, three additional pieces of information were gathered that confirm the plan but add nuance:

1. **DPLS = DEEP+.** The HIST filename token is `DPLS` (URL/filename-safe; "+" is awkward in filenames). The product/spec name is "DEEP+". Same wire format, same Message Protocol ID `0x8005`. Filenames follow `YYYYMMDD_IEXTP1_<FEED><VERSION>.pcap.gz` so the slot uses `DPLS`. Convergent evidence: DPLS first appears in HIST listings in Jan 2025, exactly aligned with the DEEP+ spec publication date.

2. **HIST access is free and identical for all three feeds.** No additional authentication or quota beyond what TOPS / DEEP already require (none). Same JSON API at `https://iextrading.com/api/1.0/hist`, same Google Cloud Storage download URLs.

3. **DEEP+ history depth is limited to ~Jan 2025 onward.** TOPS and DEEP go back to 2017; DEEP+ has ~16 months as of the project start. Implications:
   - 30-day rolling baseline and 30-day backfill (what the scorer needs): well within DPLS available history. **No impact on v1 or phase 2.**
   - Multi-year historical analysis with DEEP+ is not possible. Would require falling back to TOPS+DEEP for pre-2025 data. The project doesn't plan multi-year analysis, so this is a constraint to record but not a blocker.
   - Phase 2 launch positioning: DEEP+ can't claim "years of order-by-order history" — only "everything DEEP+ has published." Still a real product, but the marketing changes from "deep history" to "deep granularity."

4. **Reference implementations available.** GitHub search turned up several open-source IEX parsers:
   - `WojciechZankowski/iextrading4j-hist` (Java, 22 stars, Jun 2023) — TOPS + DEEP. **Same language as us; primary cross-check target.**
   - `rob-blackbourn/iex_parser` (Python, 29 stars, Jan 2022) — TOPS + DEEP. Most-starred; useful for cross-language validation.
   - Three C++ implementations covering DEEP (`Anirudhsekar96/IEX_DEEP_HISTORICAL_DATA_PARSER`, `kushal-goenka/iex-pcap-parser`, `dhsilv/iex_deep_parser`).
   - `B1tWhys/iextool` (Python, small CLI).
   - **No existing parser for DEEP+** in any language as of search.

   Cross-check strategy for v1: parse a sample TOPS .pcap.gz with `iextrading4j-hist`, dump the message stream, run our parser against the same file, diff the outputs message-by-message. Combined with the daily-totals validator this gives us two independent correctness checks.

   For phase 2: no reference parser exists for DEEP+, so we'd be flying solo on decoder correctness. This is the strongest additional reason to ship TOPS first — TOPS work matures our shared decoder + validation infrastructure, so when DEEP+ work begins we already trust the surrounding layers and can focus purely on the new trading-message decoders and order-tracking state machine. It also positions Long Exposure as **the open-source reference implementation for DEEP+ in Java** — which directly reinforces the README's "reference implementation" framing for IEX.

5. **Bootstrap and "30-day baseline" clarified.** The scorer flags events as "unusual" by comparing to per-symbol rolling 30-trading-day averages of several metrics (daily volume, daily trade count, avg spread, halt frequency, intraday volume distribution). TimescaleDB continuous aggregates maintain these incrementally. On day 1 of launch we have zero history in DB; the bootstrap is: parse the previous 30 trading days of HIST files → ingest → baselines populate → re-score and re-narrate each of those 30 days with their now-defined baselines. Launch day shows 30 days of populated narrated archive. The same 30-day backfill is the bootstrap *and* the visible launch content. See @plan.md Days 11–13.

**Recorded URLs (for reproducibility).**

- HIST API endpoint: `https://iextrading.com/api/1.0/hist` (returns all dates) or `?date=YYYYMMDD` for one date.
- HIST file naming convention: `YYYYMMDD_IEXTP1_<FEED><VERSION>.pcap.gz` where FEED ∈ {TOPS, DEEP, DPLS}.
- IEX market data landing page: `https://iextrading.com/trading/market-data/`
- Spec PDFs (canonical IEX pages and the CDN URLs of the actual PDFs):
  - TOPS 1.66: `https://www.iex.io/documents/tops-v1-66`
  - TOPS 1.5: `https://www.iex.io/documents/tops-v1-5`
  - DEEP 1.08: `https://www.iex.io/documents/deep-v1-08`
  - DEEP+ 1.02: `https://www.iex.io/documents/iex-deep-plus-specification`
  - TOPS SNAP: `https://www.iex.io/documents/iex-tops-snap-specification` (out of scope)
  - DEEP SNAP: `https://www.iex.io/documents/iex-deep-snap-specification` (out of scope)
  - DEEP+ SNAP: `https://www.iex.io/documents/deep-plus-snap-specification` (out of scope)
- Reference parsers (sorted by relevance):
  - `https://github.com/WojciechZankowski/iextrading4j-hist` (Java, primary cross-check)
  - `https://github.com/rob-blackbourn/iex_parser` (Python, most popular)
  - `https://github.com/Anirudhsekar96/IEX_DEEP_HISTORICAL_DATA_PARSER` (C++ DEEP)
  - `https://github.com/dhsilv/iex_deep_parser` (C++ DEEP, most recent)

Local archived copies of all 7 spec PDFs live at `~/workspace/data/long-exposure/specs/`.

---

## 2026-05-10 — No frontend in this repo; surface UI through vedanta-systems

**Context.** The original Day-1 scaffold (commit `8079d64`) included a `frontend/` directory containing a Svelte 5 SPA, an nginx prod image, a Vite dev image, and a public Cloudflare hostname (`longexposure.vedanta.systems`) routed through the workspace Caddy. This violates the workspace pattern.

**The pattern.** Personal projects under `~/workspace/dev/` ship **only an API**. The unified portal at `~/workspace/dev/vedanta-systems/` (React + TypeScript + shadcn/ui, public at `vedanta.systems`) hosts a per-project browser component that consumes `/api/<project>/*` via its own nginx. found-footy and spin-cycle already follow this shape; no separate frontends exist in either repo. There is one Cloudflare tunnel for the whole portal, not one per project.

**Decided.**

- Delete the entire `frontend/` directory.
- Strip the frontend service from `docker-compose.yml` and `docker-compose.dev.yml`, including `VITE_API_BASE_URL`, the frontend bind-mount volume, and the `frontend-node-modules` named volume.
- Drop `VITE_API_BASE_URL` from `.env.example`.
- Drop the `long-exposure-{prod,dev}-frontend.luv` Caddyfile entries and the entire `longexposure.vedanta.systems` Cloudflare ingress + DNS CNAME from `deploy/INFRA-NOTES.md`. The API alone joins `proxy`.
- Rewrite the README's service-layout table, frontend section, repo-layout list, and Day-22 line.
- Day-22 work is now: nginx route + `long-exposure-browser.tsx` + App registration in `vedanta-systems`, plus production bring-up here.

**Why.** N per-project frontends means N tech stacks, N Cloudflare tunnels, N deployments, and a fragmented UX. The portal pattern is already established and working for found-footy + spin-cycle. The original scaffold appears to have been generated from the README in isolation without checking the workspace conventions.

**What's still open.** Frontend design conventions (component naming, theming, dark-mode default, score-breakdown disclosure UX) need to be decided when the `long-exposure-browser` component is actually built. Cross-reference the existing `found-footy-browser` and `spin-cycle-browser` for shape.

**Memory.** Saved as a feedback memory at `~/.claude/projects/-home-vedanta-workspace-dev-long-exposure/memory/feedback_no_frontend_in_project_repos.md` so future sessions don't repeat the mistake on this or the next project.

---

## Project-genesis decisions (carried forward from README, dated to project start: 2026-05-10)

These are the load-bearing structural choices already documented in detail in @../README.md. Captured here in summary form so this log is the canonical decision archive going forward.

### Database: Postgres 16 + TimescaleDB

Chosen over QuestDB, DuckDB, ClickHouse, InfluxDB, VictoriaMetrics, kdb+. Full alternatives analysis lives in @../README.md ("Alternatives considered (database)"). Summary: Postgres+Timescale wins on operational maturity, ecosystem, and migration tooling. The ~10–20% time-range scan and ~2–5× ingest gap vs QuestDB is irrelevant at our data scale (a few hundred MB/day, ingested once nightly).

### Data feed: TOPS only

Chosen over DEEP (depth-of-book) and DPLS (order-by-order). TOPS contains every event type Long Exposure needs (halts, trades, quotes, status, system events) and is a few hundred MB compressed per day. DEEP is ~13 TB across full history. Phase-2 candidate if liquidity-depth analysis becomes worth the cost.

### Parser language: Java 21 + Gradle Kotlin DSL

Java for pcap4j (best-of-breed pcap library), JDBC (mature Postgres ecosystem), and Temporal SDK quality. Kotlin DSL for the build because the type system surfaces config errors at evaluation time. Multi-stage Dockerfile (gradle JDK21 builder → JRE alpine + libpcap) so dev needs no host-side JDK install.

### Workflow orchestration: Temporal

Chosen for durable workflow execution, automatic retries, activity-level fault isolation, and full execution history. Each pipeline stage is an activity. Long-running activities heartbeat to avoid spurious retries. Temporal metadata lives in its own Postgres (separate from the events DB) per the upstream image's recommendation.

### LLM: Qwen3.5-122B on `llama-large.joi`

Existing homelab infrastructure on `joi`. The honest justification is data sovereignty + zero-marginal-cost iteration on prompts, plus the homelab-systems showcase. (Cost savings are rounding error at 5–50 narrations per day.) The 122B model produces materially better financial prose than smaller models — that part *does* matter for the user-facing output.
