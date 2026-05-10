# Deploy notes — what changes outside this repo

The application code is fully contained in this repo. The host-level changes
needed to make Long Exposure reachable on the tailnet (and consumed by
`vedanta.systems`) live in the workspace's `~/workspace/proxy/` infra and
in `~/workspace/dev/vedanta-systems/`. They aren't tracked here because
they're cross-cutting, but they're listed so the deploy is reproducible.

There is **no** public Cloudflare hostname for Long Exposure. The unified
portal at `vedanta.systems` (served by `vedanta-systems`) hosts the UI and
proxies `/api/long-exposure/*` to this project's API container.

## 1. Caddyfile additions (luv)

Append to `~/workspace/proxy/Caddyfile`:

```caddy
# ─── long-exposure ─────────────────────────────────────────────────────────

# Tailnet routes — admin UIs and the API reachable from devices on the tailnet.
http://long-exposure-prod-api.{$BASE_DOMAIN}         { reverse_proxy long-exposure-prod-api:3001 }
http://long-exposure-prod-temporal-ui.{$BASE_DOMAIN} { reverse_proxy long-exposure-prod-temporal-ui:8080 }
http://long-exposure-prod-adminer.{$BASE_DOMAIN}     { reverse_proxy long-exposure-prod-adminer:8080 }

# Dev counterparts (when running docker-compose.dev.yml)
http://long-exposure-dev-api.{$BASE_DOMAIN}         { reverse_proxy long-exposure-dev-api:3001 }
http://long-exposure-dev-temporal-ui.{$BASE_DOMAIN} { reverse_proxy long-exposure-dev-temporal-ui:8080 }
http://long-exposure-dev-adminer.{$BASE_DOMAIN}     { reverse_proxy long-exposure-dev-adminer:8080 }
```

Then: `docker compose -f ~/workspace/proxy/docker-compose.yml restart caddy`.

## 2. vedanta-systems integration (separate repo)

In `~/workspace/dev/vedanta-systems/`:

1. Add an nginx location block to `nginx.conf` proxying `/api/long-exposure/*`
   to `long-exposure-prod-api:3001` over the shared `proxy` docker network
   (mirrors the existing `/api/found-footy/` and `/api/spin-cycle/` blocks).
2. Build a `src/components/long-exposure-browser.tsx` component that consumes
   `/api/long-exposure/v1/*` and renders the timeline UI.
3. Register the project entry in `src/App.tsx` under `~/workspace/long-exposure`.

That work is tracked in this repo's `docs/plan.md` (Day 22) and `docs/todo.md`
under "vedanta-systems integration" so the cross-repo state stays visible.

## 3. Bring up

```bash
cd ~/workspace/dev/long-exposure
cp .env.example .env
$EDITOR .env                                  # set the passwords
docker compose -f docker-compose.yml up -d --build
```

Verify:

```bash
curl -sI http://long-exposure-prod-api.luv/api/v1/health
```

Once vedanta-systems is wired up, also:

```bash
curl -sI https://vedanta.systems/api/long-exposure/v1/health
```
