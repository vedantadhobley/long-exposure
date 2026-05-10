# Decisions Log

Append-only record of architectural and operational decisions, ordered by date. When a non-obvious choice gets made, append a dated entry here with: what we decided, what we considered, why we picked the chosen path, and what still leaves open.

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
