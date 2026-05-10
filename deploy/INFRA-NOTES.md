# Deploy notes — what changes outside this repo

The application code is fully contained in this repo. The host-level changes
needed to make Long Exposure reachable on the tailnet (and consumed by
`vedanta.systems`) live in the workspace's `~/workspace/proxy/` infra and
in `~/workspace/dev/vedanta-systems/`. They aren't tracked here because
they're cross-cutting, but they're listed so the deploy is reproducible.

There is **no** public Cloudflare hostname for Long Exposure. The unified
portal at `vedanta.systems` (served by `vedanta-systems`) hosts the UI and
proxies `/api/long-exposure/*` to this project's API container.

## 1. Caddy routes (luv)

The Caddy config on luv is split per-project. This project's routes — three
prod hostnames (`api`, `temporal-ui`, `adminer`) and three dev counterparts —
live in:

```
~/workspace/proxy/caddy/caddy.d/long-exposure.caddy
```

That file is the source of truth. After editing it, reload Caddy in place:

```bash
docker exec proxy-caddy caddy reload --config /etc/caddy/Caddyfile
```

(The proxy stack uses a directory bind mount, so atomic-write edits flow
through and `caddy reload` picks them up. See `~/workspace/proxy/README.md`
for the gotcha mechanics.)

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
