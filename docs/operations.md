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

Once the Temporal workflow is wired up, kick a one-off run via the Temporal UI or:

```bash
docker compose -f docker-compose.yml exec worker \
  java -cp /app/lib/'*' com.longexposure.cli.ReplayDay --date 2026-05-09
```

(CLI entrypoint TBI — currently doesn't exist.)

The pipeline is idempotent on event-hash, so re-running won't duplicate parsed events or re-narrate cached events.

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
