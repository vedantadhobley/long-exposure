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
curl -sI http://long-exposure-prod-api.luv/api/v1/health
docker compose -f docker-compose.yml ps
```

If the Caddyfile entries from @../deploy/INFRA-NOTES.md aren't applied yet, the `*.luv` URLs will 404 — apply them and restart caddy.

## Dev stack

```bash
cd ~/workspace/dev/long-exposure
docker compose -f docker-compose.dev.yml up -d --build
```

URLs:
- API:         http://long-exposure-dev-api.luv/api/v1/health
- Temporal UI: http://long-exposure-dev-temporal-ui.luv
- Adminer:     http://long-exposure-dev-adminer.luv

The dev worker bind-mounts `./parser` at `/app` and runs `gradle --no-daemon run`. Editing Java source triggers a rebuild on container restart.

## Restart a single service

```bash
docker compose -f docker-compose.yml restart api
docker compose -f docker-compose.yml restart worker
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
