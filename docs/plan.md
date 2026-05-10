# 22-day Build Plan

The day-by-day schedule for going from "scaffolded repo" to "live narrated public daily-market feed surfaced inside vedanta.systems."

Status legend: `[ ]` not started, `[~]` in progress, `[x]` complete.

This plan is the agent-editable source of truth. The README has a condensed prose version; if they conflict, this file wins.

## Days 1–3 — Foundation

- [x] Set up Gradle Kotlin DSL project (`build.gradle.kts`, `settings.gradle.kts`)
- [x] Multi-stage Dockerfile (gradle JDK21 builder → JRE alpine + libpcap)
- [x] Open the GitHub repo public on Day 1 (MIT licensed)
- [ ] Implement `pcap.PcapReader` using pcap4j — open a `.pcap.gz` and stream packets
- [ ] Implement `transport.IexTpDecoder` — parse the 40-byte IEX-TP header
- [ ] Get raw TOPS message bytes printing to stdout from a real HIST file

## Days 4–7 — TOPS parser + validation harness (in parallel)

- [ ] Implement `tops.TopsMessageRouter` — dispatch on the 1-byte message type after the 2-byte length prefix
- [ ] Decoder + value object for each TOPS message type:
  - [ ] System Event (`S`)
  - [ ] Security Directory (`D`)
  - [ ] Trading Status (`H`)
  - [ ] Quote Update (`Q`)
  - [ ] Trade Report (`T`)
  - [ ] Trade Break (`B`)
  - [ ] Official Price (`X`)
- [ ] Validate price decoding (8-byte signed int with 4 implied decimals, little-endian)
- [ ] Unit tests for each decoder (junit-jupiter is already in deps)
- [ ] **`validation.DailyTotalsValidator`** — cross-checks against IEX's published per-symbol summaries. Don't wait until the end; every new decoder gets a paired check.

## Days 8–10 — Postgres + TimescaleDB + storage

- [ ] Write `parser/src/main/resources/schema.sql` defining:
  - [ ] `events` table → `create_hypertable` on trade-time column
  - [ ] `narratives` table keyed by event hash
  - [ ] Continuous aggregates for the T-30 baselines (symbol × metric)
- [ ] Apply schema on worker startup (or via a one-shot Temporal activity)
- [ ] Implement `storage.TimescaleWriter` — JDBC `COPY` for bulk insert from the parser
- [ ] Parse a full day's HIST file end-to-end and verify the data lands. Spot-check via Adminer.

## Days 11–13 — Baseline data + bootstrap

- [ ] Implement the rolling-baseline calculation (wired to TimescaleDB continuous aggregates)
- [ ] Backfill against the last 30 trading days of HIST data (one-time bootstrap)
- [ ] Spot-check baselines: a real halt from the past month should score high; a routine quote on a quiet day should score low

## Days 14–17 — Event scoring

- [ ] Implement `scoring.EventScorer` with the significance dimensions documented in the README:
  - [ ] Event-type weighting
  - [ ] Ticker liquidity tier
  - [ ] Deviation from baseline
  - [ ] Time-of-day weighting
  - [ ] Duration
- [ ] Every event surfaces its score breakdown in the data model (visible in API responses for inspection)
- [ ] Tune until the top-N events on a sample day pass the "would I read this?" check

## Days 18–19 — LLM narration

- [ ] Implement `NarrateEventsActivity` calling `llama-large.joi` (OpenAI-compatible)
- [ ] Cache by event hash in the `narratives` table
- [ ] Prompt engineering: factual, plain-language, refuse on incomplete data
- [ ] Generate + review narratives for a week of historical events; iterate prompt

## Days 20–21 — Temporal + API

- [ ] Wire `Main.java` as the Temporal worker registration (replaces the Day-1 stub)
- [ ] Implement the workflow + activity classes (see @architecture.md for the activity list)
- [ ] Retry policies + heartbeating per activity
- [ ] Implement the FastAPI v1 endpoints listed in @../README.md
- [ ] End-to-end pipeline run on a single trading day

## Day 22 — vedanta-systems integration + deploy

Cross-repo work in `~/workspace/dev/vedanta-systems/`:

- [ ] nginx location for `/api/long-exposure/*` → `long-exposure-prod-api:3001`
- [ ] `src/components/long-exposure-browser.tsx` — timeline UI consuming `/api/long-exposure/v1/*`
- [ ] Register project entry in `src/App.tsx` under `~/workspace/long-exposure`
- [ ] (Optional) `LongExposureContext` if streaming is needed — likely not, daily updates

In this repo:

- [ ] Production bring-up on luv (apply Caddyfile entries from @../deploy/INFRA-NOTES.md, fill `.env`, `docker compose up -d`)
- [ ] Backfill the previous 30 days end-to-end so launch day already has narrated history
- [ ] Public launch verification: `curl https://vedanta.systems/api/long-exposure/v1/health`
