# Operations Runbook

Common operational tasks. Companion to @../deploy/INFRA-NOTES.md (host-level setup) — this doc is the day-to-day reference.

## First-time bring-up (luv)

```bash
cd ~/workspace/dev/long-exposure
cp .env.example .env
$EDITOR .env                                  # set POSTGRES_PASSWORD + TEMPORAL_POSTGRES_PASSWORD
docker compose -f docker-compose.yml up -d --build
```

Verify:

```bash
# Public API surface lives in vedanta-systems, not this repo:
curl -sI https://vedanta.systems/api/long-exposure/health
docker compose -f docker-compose.yml ps
```

If the Caddyfile entries from @../deploy/INFRA-NOTES.md aren't applied yet, the `*.luv` URLs will 404 — apply them and restart caddy.

## Dev stack

```bash
cd ~/workspace/dev/long-exposure
docker compose -f docker-compose.dev.yml up -d --build
```

URLs:
- Temporal UI: http://long-exposure-dev-temporal-ui.luv
- Adminer:     http://long-exposure-dev-adminer.luv

No `long-exposure-{dev,prod}-api.luv` — public `/api/long-exposure/*` is served by vedanta-systems' unified API, which connects directly to long-exposure's postgres via the shared `luv-{dev,prod}` network. To probe the API endpoints during dev: `docker exec vedanta-systems-dev-api wget -qO- http://localhost:3001/api/long-exposure/health`.

The dev worker bind-mounts `./parser` at `/app` and runs `gradle --no-daemon run`. Editing Java source triggers a rebuild on container restart.

## Restart a single service

```bash
docker compose -f docker-compose.yml restart api
docker compose -f docker-compose.yml restart worker
```

## Restart the whole dev stack (recover any stopped services)

When iterating on a specific service (e.g. `docker compose ... up -d postgres worker` after a compose-file change), `up -d <service>` only starts the named services and their declared dependencies. Anything else that was already stopped — like `temporal-ui` or `adminer`, which aren't dependencies of `worker` — will remain stopped.

To recover a clean dev stack with everything running, use the no-args form:

```bash
docker compose -f docker-compose.dev.yml up -d
```

This is idempotent: services that are already running keep running; only stopped/missing containers get started. Use it as the default "bring things back up" command after any targeted restart sequence. The full prod equivalent is `docker compose -f docker-compose.yml up -d`.

Diagnose what's stopped vs running:

```bash
docker compose -f docker-compose.dev.yml ps -a   # -a includes stopped containers
```

## Rebuild after a code change (prod stack)

```bash
docker compose -f docker-compose.yml up -d --build api worker
```

## Schema operations

Schema target path: `parser/src/main/resources/schema.sql` (not yet committed).

Apply manually via Adminer or psql inside the postgres container:

```bash
docker compose -f docker-compose.yml exec postgres \
  psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /tmp/schema.sql
```

Long-term, schema application becomes a one-shot Temporal activity that runs on worker startup if a `schema_version` row indicates a pending migration.

## Reset the dev events DB

```bash
docker compose -f docker-compose.dev.yml down
docker volume rm long-exposure-dev-postgres-data
docker compose -f docker-compose.dev.yml up -d
```

`temporal-postgres` is a separate volume (`long-exposure-dev-temporal-postgres-data`) and won't be touched. Reset it the same way if Temporal metadata gets stuck.

## Replay a single trading day

Every phase is an independently-invokable workflow. Replay the whole day or one
phase via the Temporal UI or `temporal workflow start` (input is a `[Y,M,D]`
array for the phase workflows; the daily orchestrator takes an input object):

```bash
# whole day (re-download/parse only if not already loaded)
docker exec long-exposure-dev-temporal temporal workflow start \
  --task-queue long-exposure-daily-pipeline --type DailyPipelineWorkflow \
  --workflow-id daily-20260508 \
  --input '{"targetDate":[2026,5,8],"pollUntilReady":false,"forceReingest":false,"runRetentionSweep":false}'

# a single phase against already-loaded data (no re-download/parse):
docker exec long-exposure-dev-temporal temporal workflow start \
  --task-queue long-exposure-daily-pipeline --type ScoreWorkflow \
  --workflow-id score-20260508 --input '[2026,5,8]'
# … likewise NarrateWorkflow / InterpretWorkflow / SynthesizeDayWorkflow / AggregateWeekWorkflow
```

The pipeline is idempotent: parse pre-cleans by `trading_date`; DESCRIBE /
INTERPRET / AGGREGATE are **content-addressed** (`event_hash` /
`interpretation_hash` / `content_hash`), so re-running skips the LLM call for any
event/week whose inputs are unchanged and only re-does what actually changed.

## Re-run / rebuild the dataset across days

To re-apply accumulated breakdown/prompt changes uniformly across the loaded
days (without re-downloading/parsing the wire data), use the driver:

```bash
bash scripts/rerun-dataset.sh        # logs to scripts/rerun-dataset.log
tail -f scripts/rerun-dataset.log
```

Per day it runs `ScoreWorkflow → NarrateWorkflow → InterpretWorkflow →
SynthesizeDayWorkflow`, then `AggregateWeekWorkflow` per ISO week at the end
(chronological, so each week reads the prior). It waits for each workflow to
reach a terminal state before starting the next — this enforces the
one-LLM-workflow-at-a-time rule (see below). The content-addressed skip + the
verifier-retry make it incremental and self-healing: only changed events hit the
LLM, and transient verifier failures clear on a re-roll. Heavy: each day's
narrate stage is ~30–40 min when most events changed (e.g. a formatting
cache-bust touches every notional event), so a full ~2-week re-run is several
hours — run it overnight.

## Prune stale narration rows (after a re-run)

Re-runs insert a new `narratives` / `interpretations` / `weekly_aggregate` row
per changed content hash and leave the superseded ones behind. The public API
dedupes by content-key (latest `verifier_passed` reachable from
`selected_events`), so orphans never reach the UI — but to keep the tables lean,
run the prune after a re-run:

```bash
# DRY RUN first — review the keep/delete counts:
docker exec -i long-exposure-dev-postgres psql -U leuser -d longexposure \
  < docs/sql/prune-stale-narrations.sql
# then uncomment the DELETE block (wrapped in BEGIN/COMMIT) and re-run it.
```

It keeps, per content-key still reachable from `selected_events`, the latest
`verifier_passed` row (or latest overall if none passed) and deletes the rest.
Idempotent. The narrative-layer tables are otherwise kept indefinitely (only the
heavy wire substrate ages out on the 2-week retention sweep).

## Tail logs

```bash
docker compose -f docker-compose.yml logs -f worker
docker compose -f docker-compose.yml logs -f api
```

Aggregated logs also flow to Loki at `monitor-loki.luv` automatically via Promtail (workspace-shared, no per-service setup).

## Adminer access

http://long-exposure-prod-adminer.luv — server is preset to `long-exposure-prod-postgres`. Credentials from `.env`.

## Inspect Temporal workflow state

http://long-exposure-prod-temporal-ui.luv — full execution history, retry counts, activity heartbeats. The source of truth for pipeline-execution debugging.

## Gotcha — JDBC `?` parameter placeholders clash with JSONB key-exists operators

PostgreSQL's JSONB operators `?`, `?|`, and `?&` (check whether a key or set of keys exists in a JSONB object) **collide with JDBC's `?` parameter placeholder** in prepared statements. The driver scans the SQL string for `?` characters and binds them to `setX()` calls in order. A query like

```sql
UPDATE scored_events
   SET breakdown = breakdown - 'co_occurring'
 WHERE trading_date = ?
   AND breakdown ? 'co_occurring'
```

with `st.setObject(1, date)` errors out at execution time with

```
PSQLException: No value specified for parameter 2.
```

because JDBC interpreted both `?` as positional placeholders.

**Workarounds** (any of these is fine):

1. **`->` accessor returning NULL** (clearest, what we use):
   ```sql
   WHERE breakdown->'co_occurring' IS NOT NULL
   ```
2. **`jsonb_exists(col, key)` function form**:
   ```sql
   WHERE jsonb_exists(breakdown, 'co_occurring')
   ```
3. **Escaped operator** (JDBC unescapes `??` → `?` at parse time):
   ```sql
   WHERE breakdown ?? 'co_occurring'
   ```
4. **`@>` containment** when checking for both key + value:
   ```sql
   WHERE breakdown @> '{"co_occurring": {}}'::jsonb
   ```

Hit this in `EnrichWithCoOccurrenceActivityImpl.preClean()` 2026-05-20 — wasted ~13 minutes of compute on a re-run before catching it.

## Gotcha — `restart: unless-stopped` doesn't recover from host-port bind failures

A container in `Exited (128)` with `restartCount=0` is the smoking gun. Docker's `restart` policy only handles **runtime process exits** — when the container started, ran, and then the process died. **Port-bind failures during container setup happen BEFORE the process starts and are NOT covered by the restart policy.** The container goes to `Exited (128)`, `restartCount` stays 0, and it sits there forever until someone runs `docker compose up -d` (which recreates).

This bit us twice — May 17 and May 20 — on both `proxy-dnsmasq` containers (luv and joi). Trigger: a system update SIGTERMs docker, tailscaled restarts, the tailscale IP is briefly unassigned, docker tries to recreate dnsmasq and fails to bind `${HOST_TAILNET_IP}:53` with `cannot assign requested address`. DNS dies for everything depending on `*.luv` / `*.joi` resolution.

**Codified fix (applied 2026-05-20 to both luv and joi `~/workspace/proxy/docker-compose.yml`)**: dnsmasq uses `network_mode: host` instead of docker port-publishing, and binds itself via `--listen-address=${HOST_TAILNET_IP} --listen-address=127.0.0.1 --bind-dynamic`. The bind decision now lives inside dnsmasq, where `--bind-dynamic` watches for the listed IPs appearing/disappearing and rebinds accordingly. Any future failure is a runtime exit, which `restart: unless-stopped` actually covers.

Why `0.0.0.0:53` doesn't work: `systemd-resolved` already owns `127.0.0.53:53` on both hosts, so docker can't publish `0.0.0.0:53` without conflict. Host networking sidesteps this by letting dnsmasq bind only to the tailscale IP and 127.0.0.1.

If a docker container ever lands in `Exited` with `restartCount=0`, treat it as a setup-time (not runtime) failure and look at `docker inspect <name> --format '{{.State.Error}}'` for the actual reason.

## Gotcha — restart the worker after any activity/workflow source change

Editing a class under `parser/src/main/java/com/longexposure/temporal/` does
NOT automatically take effect in the running worker JVM, even with the dev
stack's bind-mounted source. The JVM loads classes into metaspace at startup
and doesn't reload them when the file on disk changes. Re-running `gradle
compileJava` while the worker is up updates the `.class` files on disk but
the running JVM keeps using the version it already loaded.

Worse: there's a window — observed 2026-05-28 with Phase 9-A — where
restarting the worker container within ~minute of a recent source change can
hit a Gradle incremental-compile race that loads the **previous** class
version. The container starts → `gradle --no-daemon run` decides nothing
needs recompiling (file mtimes look "recent enough") → JVM loads the stale
class. Hours pass. The new code looks committed and deployed but isn't
running. Smoking gun: behavior matches the *old* code path, often a log
message that should no longer appear.

**Protocol after any source change to an activity or workflow class:**

```bash
# 1. Restart the worker
docker restart long-exposure-dev-worker

# 2. Wait for boot
docker logs long-exposure-dev-worker --since 3m 2>&1 | grep "workers started"

# 3. Verify the new class symbols are in /app/build (post-compile state)
docker exec long-exposure-dev-worker sh -c \
  'strings /app/build/classes/java/main/com/longexposure/temporal/activities/MyChangedActivityImpl.class | grep my-new-symbol'

# 4. Confirm the WorkerMain JVM started AFTER the source change
docker exec long-exposure-dev-worker sh -c \
  "stat -c '%y' /proc/\$(pgrep -f longexposure.Main | tail -1)"
```

If step 3 finds the symbol but the behavior still doesn't match, the JVM
loaded a stale `/tmp/cc/build/classes/` version. Force-recompile from the
host before the next restart:

```bash
docker exec -w /app long-exposure-dev-worker gradle --no-daemon --quiet compileJava
docker restart long-exposure-dev-worker
```

The pre-restart compile pre-stages `/app/build/classes/` with the fresh code
so the JVM-start incremental check can't miss it.

## Operational rule — never run two LLM-bearing workflows concurrently

**`llama-large.joi` is a single-GPU local model. Max throughput is 2 concurrent decode streams; above that, throughput collapses rather than scales.** The `LlamaClient.Semaphore(2, fair)` enforces this *within* a single workflow's activity fan-out, but it does NOT prevent two separate workflows from each spawning activities that compete for the same 2 slots.

**Three LLM-bearing workflows exist today**: `NarrateWorkflow` (DESCRIBE stage), `InterpretWorkflow` (INTERPRET stage), and `SynthesizeDayWorkflow` (SYNTHESIZE stage). All three dispatch on the same `NARRATION_TASK_QUEUE` whose worker is capped at 2 concurrent activities, and all three pass through the JVM-wide `Semaphore(2, fair)` in `LlamaClient`.

If two LLM-bearing workflows are running concurrently (e.g. two `NarrateWorkflow` instances for different dates, or `NarrateWorkflow` + `InterpretWorkflow`), the semaphore serializes them at the activity level — but each workflow's sliding-window cap (`MAX_IN_FLIGHT=2`) keeps trying to push activities into a permit set already at capacity. The result is:

- Both workflows technically "running" but only one making progress at a time
- Confusing Temporal UI showing 4 pending activities, only 2 active
- Wasted wall-clock — the second workflow waits without doing useful work
- Eventually some activities will time out (start-to-close timer runs during the wait)

**The rule**: only one LLM-bearing workflow runs at a time. Before kicking any LLM workflow (`NarrateWorkflow`, `InterpretWorkflow`, `SynthesizeDayWorkflow`) — or `DailyPipelineWorkflow`, which contains all three as child workflows — confirm no other LLM-bearing execution is in flight via:

```bash
docker exec long-exposure-dev-temporal temporal workflow list \
  --query "(WorkflowType='NarrateWorkflow' OR WorkflowType='InterpretWorkflow' OR WorkflowType='SynthesizeDayWorkflow' OR WorkflowType='DailyPipelineWorkflow') AND ExecutionStatus='Running'"
```

Should return no rows. If it does, terminate or wait for that workflow before starting another.

Non-LLM workflows (`DownloadWorkflow`, `ParseWorkflow`, `ValidateWorkflow`, `ScoreWorkflow`, `MaterializeWorkflow`, `SelectWorkflow`, `CleanupWorkflow`, `RefreshSymbolsWorkflow`) can run concurrently with LLM workflows since they don't touch joi. During multi-day backfills, this means the LLM-free phases (download / parse / validate / score / select) can be pipelined for multiple dates in parallel even though the LLM-bound stages (DESCRIBE / INTERPRET / SYNTHESIZE) must serialize.

## LLM endpoint (joi)

Qwen3.5-122B-A10B served from joi (Framework Desktop, 128 GB unified, Strix Halo APU) via llama.cpp. Observed throughput **~23 tokens/sec**. Budget-comfortable for 5–50 narrations/day at ~150 tokens each.

Verify joi is reachable from this host:

```bash
curl -s http://joi.<tailnet>:3101/v1/models | jq '.data[].id'
```

From inside the worker container (which is on the `luv-prod` / `luv-dev` network for tailnet egress):

```bash
docker compose -f docker-compose.yml exec worker \
  curl -s http://llama-large.joi/v1/models | jq '.data[].id'
```
