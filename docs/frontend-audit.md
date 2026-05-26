# Frontend Audit — vedanta-systems `long-exposure-browser` (2026-05-25)

Read-only audit of the current frontend integration in `~/workspace/dev/vedanta-systems`,
done during launch week to turn Days 7–8 from "figure it out" into an execution list.

**Files in scope** (all exist, all small):
- `src/server/routes/long-exposure.ts` (245 lines) — Express router, `/api/long-exposure/*`
- `src/components/long-exposure-browser.tsx` (148 lines) — the React component
- `src/types/long-exposure.ts` (49 lines) — response shapes
- wiring: `src/server/index.ts` mounts the router; `src/App.tsx` renders the component under `~/workspace/long-exposure`

**State today:** a working **minimal v1** — fetch `/latest` → fetch `/day/:date` → render narrations grouped by scorer. Endpoints exist for health/dates/latest/day/symbol/event. It predates INTERPRET, SYNTHESIZE, AGGREGATE, VolumeDeviation, and the prompt-version-iteration reality. The gaps below are the delta to a launch-ready browser.

---

## A. Correctness bugs (fix first — latent now, visible the moment a day is re-narrated)

- [ ] **A1 — No latest-per-`selected_id` dedup (the big one).** `/day/:date`, `/symbol/:symbol`, and the `/dates` + `/health` counts all `SELECT … FROM narratives WHERE verifier_passed` with no dedup. `narratives` carries **multiple rows per event** from prompt-version iterations (~2,560 rows across 6 days vs ~819 real events; 05-08 alone was re-narrated through v3→v7). So `/day` on any iterated day returns **duplicate event cards**, and the counts are inflated. Fix: `DISTINCT ON (n.selected_id) … ORDER BY n.selected_id, n.created_at DESC` (mirror `SynthesizeDayActivityImpl.loadEventsForDay`), then re-sort. The backfill days (05-18→22) happen to be narrated once so they look fine today — this bites silently on re-narration.
- [ ] **A2 — `/day` join is on the wrong column → `narration_rank` is dead.** The query joins `selected_events se ON se.event_id = n.selected_id`, but `narratives.selected_id` holds a `selected_events.selected_id` value (confirmed by the SYNTHESIZE loader's `n.selected_id = se.selected_id`), while `se.event_id` is a `scored_events.event_id`. Different ID spaces → the join never matches → `se.narration_rank` is always NULL → `ORDER BY … narration_rank NULLS LAST, n.score DESC` silently falls back to score-desc. Functionally tolerable (score-desc is reasonable) but the join is incorrect. Fix: `se.selected_id = n.selected_id`, or drop the join and order by score.

## B. Shipped data with no endpoint (the new stages aren't surfaced at all)

- [ ] **B1 — SYNTHESIZE not served.** `daily_synthesis` (the top-of-page themes paragraph — the headline feature) has no route. Add `GET /synthesis/:date` (or fold a `synthesis` field into `/day`). Today the component header is hardcoded boilerplate where the synthesis should be.
- [ ] **B2 — INTERPRET not served.** `interpretations.interpretation` is never returned. `/day` and `/event` return only the DESCRIBE `prose`. Drill-down should show DESCRIBE **and** INTERPRET. LEFT JOIN `interpretations` (latest-per-`selected_id`, same dedup as A1) into `/day` and `/event`.
- [ ] **B3 — AGGREGATE not served.** `weekly_aggregate` (weekly themes, just shipped) has no route. Add `GET /aggregate/latest` + `GET /aggregate/:weekStart`.

## C. Schema/code drift

- [ ] **C1 — Scorer label/order list is missing `volume_deviation`.** `SCORER_LABELS` + `SCORER_ORDER` in the component hardcode the original 7 scorers. The new inter-day scorer's events render with the raw key `volume_deviation`, unlabeled and sorted last. Add a label ("Volume surges" / "Unusual volume") + order entry. Make the component tolerant of unknown scorer keys (fall back to the raw id rather than dropping them — `SCORER_ORDER.filter` currently drops any group not in the list).
- [ ] **C2 — `types/long-exposure.ts` lags the schema.** `LongExposureNarrative.scorer_id` doc-comment enumerates 7 scorers (no `volume_deviation`); no types for synthesis / interpretation / weekly aggregate. Update when B1–B3 land.

## D. The three "wow" items (Days 7–8 build — bigger than wiring)

- [ ] **D1 — Visible grounding (#2, cheapest, data already there).** `/event/:id` already returns `breakdown` + `blueprint` + `verifier_passed` + `verifier_notes`. The component renders none of it — `EventCard` shows only symbol + ts + prose, and `/event/:id` is unused. Build an expandable card: "✓ every figure traces to IEX data" badge → expand to the breakdown JSON. **No API change needed**; pure component work. Highest wow-per-effort.
- [ ] **D2 — Intraday chart with event markers (#1, the centerpiece).** No endpoint serves intraday price/volume series. New endpoint: `GET /intraday/:date/:symbol` returning a `time_bucket`'d price/volume series (from `trades`/`quotes`) + event markers; new chart component (pick a lib — recharts/visx). Biggest new build; the "long exposure photograph." Caveat: depends on wire data, which is only retained 2 weeks — chart only works for recent days.
- [ ] **D3 — Recursive drill to raw events (#4, first to drop under time pressure).** No endpoint serves the raw atomic events (`orders_add` etc.) behind a narration. New endpoint querying by `source_refs` / event window + UI. Same 2-week-retention caveat as D2. Per the launch-sprint buffer note, this is the first wow item to defer if Days 7–8 run tight.

## E. UX gaps (component is "minimal v1" by its own comment)

- [ ] **E1 — No date picker.** Only ever shows `/latest`. `/dates` endpoint exists, unused. Add a date selector.
- [ ] **E2 — No symbol filter / search.** `/symbol/:symbol` exists, unused. Add ticker search (could autocomplete off the `symbols` table — needs a small `/symbols` endpoint).
- [ ] **E3 — No drill-down panel.** `EventCard` is static; `/event/:id` unused. (Pairs with D1.)
- [ ] **E4 — Timestamps shown as raw UTC slice** (`event_ts.slice(11,19) UTC`). Should be ET, formatted — the breakdown already carries ET fields, or format client-side.
- [ ] **E5 — Grouped-by-scorer only; no chronological/timeline view.** concepts.md notes a chronological sort is a trivial API tweak; the timeline framing is core to the product's identity.
- [ ] **E6 — Dark-mode / mobile not verified.** Uses `corpo-text` theme classes; needs a responsiveness + dark-mode pass (can't assess statically).

---

## Suggested Day 7 / Day 8 split

**Day 7 — API + wiring (mostly mechanical SQL/route edits):**
A1 (dedup, the critical fix) · A2 (join) · B1 (synthesis endpoint) · B2 (interpret in `/day`+`/event`) · B3 (aggregate endpoint) · C1/C2 (volume_deviation label + types). All small; the dedup pattern is already written in the SYNTHESIZE loader.

**Day 8 — component build:**
synthesis paragraph at top (B1) · drill-down panel + visible-grounding badge (D1/E3 — data ready) · volume_deviation group (C1) · date picker (E1) · symbol search (E2) · ET timestamps (E4) · dark-mode/mobile pass (E6).

**Stretch (keep #1, drop #4 first if tight):**
D2 intraday chart (the centerpiece visual — new endpoint + chart lib) · D3 recursive raw-event drill (new endpoint; first to cut).

**Note:** D2/D3 depend on wire data that the 2-week retention drops — both only work for recent days. Fine for a live product (you chart "today"); just not for the deep archive.
