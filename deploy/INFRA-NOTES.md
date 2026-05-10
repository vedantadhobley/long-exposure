# Deploy notes — what changes outside this repo

The application code is fully contained in this repo. The host-level changes
needed to make Long Exposure publicly reachable as `longexposure.vedanta.systems`
live in the workspace's `~/workspace/proxy/` infra (and `~/.cloudflared/` for
the public ingress). They aren't tracked here because they're cross-cutting,
but they're listed here so the deploy is reproducible.

## 1. Caddyfile additions (luv)

Append to `~/workspace/proxy/Caddyfile`:

```caddy
# ─── long-exposure ─────────────────────────────────────────────────────────

# Public site (terminated by cloudflared; see Cloudflare config below).
http://longexposure.vedanta.systems {
    reverse_proxy long-exposure-prod-frontend:3000
}

# Tailnet routes — admin UIs and project frontends reachable from devices
# on the tailnet without going through Cloudflare.
http://long-exposure-prod-frontend.{$BASE_DOMAIN}    { reverse_proxy long-exposure-prod-frontend:3000 }
http://long-exposure-prod-api.{$BASE_DOMAIN}         { reverse_proxy long-exposure-prod-api:3001 }
http://long-exposure-prod-temporal-ui.{$BASE_DOMAIN} { reverse_proxy long-exposure-prod-temporal-ui:8080 }
http://long-exposure-prod-adminer.{$BASE_DOMAIN}     { reverse_proxy long-exposure-prod-adminer:8080 }

# Dev counterparts (when running docker-compose.dev.yml)
http://long-exposure-dev-frontend.{$BASE_DOMAIN}    { reverse_proxy long-exposure-dev-frontend:3000 }
http://long-exposure-dev-api.{$BASE_DOMAIN}         { reverse_proxy long-exposure-dev-api:3001 }
http://long-exposure-dev-temporal-ui.{$BASE_DOMAIN} { reverse_proxy long-exposure-dev-temporal-ui:8080 }
http://long-exposure-dev-adminer.{$BASE_DOMAIN}     { reverse_proxy long-exposure-dev-adminer:8080 }
```

Then: `docker compose -f ~/workspace/proxy/docker-compose.yml restart caddy`.

## 2. Cloudflare tunnel ingress (luv)

Append to the `ingress:` list in `~/.cloudflared/config.yml` *before* the
catch-all `http_status:404` rule:

```yaml
- hostname: longexposure.vedanta.systems
  service: http://proxy-caddy:80
```

Then in the Cloudflare dashboard for the tunnel `vedanta-systems-prod`, add
a public hostname mapping `longexposure.vedanta.systems` → `http://localhost`
(any value; the tunnel reads ingress rules from the file, this dashboard
entry just gates the DNS).

Restart cloudflared:

```bash
docker compose -f ~/workspace/proxy/docker-compose.yml restart cloudflared
```

## 3. DNS

In Cloudflare DNS for `vedanta.systems`: a `CNAME` record
`longexposure → <tunnel-uuid>.cfargotunnel.com` (proxied = on). The tunnel
UUID is in `~/.cloudflared/config.yml` (`tunnel:` line) and must match the
JSON credentials file's UUID.

## 4. Bring up

```bash
cd ~/workspace/dev/long-exposure
cp .env.example .env
$EDITOR .env                                  # set the passwords
docker compose -f docker-compose.yml up -d --build
```

Verify:

```bash
curl -sI http://long-exposure-prod-frontend.luv/
curl -sI http://long-exposure-prod-api.luv/api/v1/health
curl -sI https://longexposure.vedanta.systems/
```
