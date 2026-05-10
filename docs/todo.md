# TODO

The persistent, project-scoped scratchpad. Cross-references @plan.md for phase context and @decisions.md for the reasoning behind structural choices.

This file is editable by both humans and the agent during sessions. Append-friendly; use markdown checkboxes; reference file:line where helpful.

---

## Blockers (must resolve before code starts)

- [x] ~~Create `.env` from `.env.example`~~ (2026-05-10) — `leuser/lepass` for events DB, `temporal/lepass` for Temporal metadata, matching the found-footy `ffuser/ffpass` convention.
- [ ] **Apply Caddyfile entries** from @../deploy/INFRA-NOTES.md to `~/workspace/proxy/Caddyfile`, then restart caddy. Until this lands, even the API health-check is unreachable at `*.luv` URLs.
- [ ] **Write `parser/src/main/resources/schema.sql`** — there is no SQL committed yet. Hypertable + continuous-aggregate definitions need to live in the repo before Day 8 lands.

## Day 1–3 (foundation work, in progress)

- [ ] Implement `pcap/PcapReader.java` — currently a one-line stub
- [ ] Implement `transport/IexTpDecoder.java` — currently a one-line stub
- [ ] Replace `Main.java`'s "stub" banner with a meaningful Day-1 smoke test (read a sample pcap, print the first 5 IEX-TP headers)

## Stub-fill backlog (Java)

All currently one-line placeholders under `parser/src/main/java/com/longexposure/`:

- [ ] `pcap/PcapReader.java`
- [ ] `transport/IexTpDecoder.java`
- [ ] `tops/TopsMessageRouter.java`
- [ ] `tops/messages/TopsMessage.java` (base interface)
- [ ] `tops/messages/QuoteUpdate.java`
- [ ] `tops/messages/TradeReport.java`
- [ ] `tops/messages/TradingStatus.java`
- [ ] `tops/messages/SecurityDirectory.java`
- [ ] `tops/messages/SystemEvent.java`
- [ ] Trade Break and Official Price decoders (no stubs exist yet — add them)
- [ ] `validation/DailyTotalsValidator.java`
- [ ] `storage/TimescaleWriter.java`
- [ ] `scoring/EventScorer.java`

## Temporal worker wiring (when stubs are filled)

- [ ] Replace `Main.java`'s `Thread.currentThread().join()` keep-alive with Temporal worker registration (connect to `TEMPORAL_HOST`, register activities, block on the worker)
- [ ] Define the workflow class with the activity sequence in @architecture.md
- [ ] Per-activity retry policies + timeouts + heartbeat configuration

## API endpoints (none implemented yet)

Endpoint stubs are listed as comments in `api/src/long_exposure_api/main.py`. To implement:

- [ ] `GET /api/v1/market/today`
- [ ] `GET /api/v1/market/{date}`
- [ ] `GET /api/v1/market/{date}/events`
- [ ] `GET /api/v1/ticker/{symbol}/history`
- [ ] `GET /api/v1/event/{event_id}` — must include the score breakdown

## vedanta-systems integration (cross-repo, Day 22)

Work happens in `~/workspace/dev/vedanta-systems/` — track here so it's visible from this project's state:

- [ ] Add `/api/long-exposure/*` location to vedanta-systems' `nginx.conf` (mirror existing `/api/found-footy/*`, `/api/spin-cycle/*`)
- [ ] Build `src/components/long-exposure-browser.tsx` — timeline UI
- [ ] Register project entry in `src/App.tsx` under `~/workspace/long-exposure`
- [ ] Add the GitHub link entry in `App.tsx`'s `projectGithubLinks` map
- [ ] Sanity-check end-to-end: `curl https://vedanta.systems/api/long-exposure/v1/health`

## Cleanup / scaffolding follow-ups

- [x] ~~Remove the erroneous Svelte SPA scaffold~~ (2026-05-10)
- [x] ~~Strip frontend service from compose files~~ (2026-05-10)
- [x] ~~Drop the `longexposure.vedanta.systems` Cloudflare route from INFRA-NOTES~~ (2026-05-10)
- [x] ~~Drop `VITE_API_BASE_URL` from `.env.example`~~ (2026-05-10)
- [x] ~~Update README service-layout table, frontend section, day-22 plan, repo layout~~ (2026-05-10)
- [ ] Resolve the `pipeline/` references in old README copy — the worker *is* the parser, no separate `pipeline/` dir. (README has been corrected; double-check once `Main.java` becomes the worker entry point that no comments still reference `pipeline/`.)
- [ ] Decide whether the `frontend/Dockerfile` `EXPOSE 3000` declaration in any leftover doc still makes sense — probably needs a final pass.

## Deferred / phase 2

- [ ] DEEP feed (depth-of-book) ingestion if liquidity analysis is added
- [ ] Real-time streaming (requires the IEX SIP feed; different licensing model)
- [ ] Multi-exchange comparison
- [ ] User accounts / saved searches / alerts
- [ ] DuckDB as a dev-time companion against parquet exports of the events table
