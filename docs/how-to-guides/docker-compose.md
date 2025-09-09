# Run with Docker Compose

This guide shows how to run the project using Docker Compose.

The `PHOENIXD_SERVICE` environment variable controls which Phoenixd backend is used. It defaults to `phoenixd-mock` for local development. For production, set `PHOENIXD_SERVICE=phoenixd-rest`.

Profiles and services:
- dev: runs all services except `phoenixd-rest` (uses `phoenixd-mock`).
- prod: runs all services except `phoenixd-mock` (uses `phoenixd-rest`).

Services and ports (host → container):
- cashu-mint-rest: 7777 → 7777
- cashu-gateway-rest: 8889 → 8889 (dev provider: mock; prod: phoenixd)
- cashu-vault-jpa: 8888 → 8888
- phoenixd (mock or rest): 9740 → 9740
- cashu-mint-db (Postgres): 55432 → 5432 (db `cashu_mint`, user/pass `postgres`)
- cashu-vault-db (Postgres): 55433 → 5432 (db `cashu_vault`, user/pass `postgres`)

Health checks and startup order:
- Postgres containers have healthchecks; dependent services wait for DB readiness.
- Java services expose `/actuator/health` (and readiness when probes are enabled).
- Compose healthchecks for Gateway and Vault attempt HTTP checks using `curl` or `wget`. If neither client exists, the healthcheck fails but the service can still be functional.
- To avoid blocking local development when a container lacks an HTTP client, the mint service waits for the databases to be healthy, and only requires Gateway and Vault to be started.

Before running, pull the latest images:

```bash
docker compose --profile dev pull
```

Example commands:

```bash
docker compose --profile dev up
PHOENIXD_SERVICE=phoenixd-rest PHOENIXD_API_KEY=your-key \
  docker compose --profile prod up
```

When invoking Docker with `sudo`, pass environment variables explicitly or use `sudo -E` so the environment and home directory of the calling user are preserved.
