# Run the Admin REST with Docker

This tutorial shows how to build and run the Admin REST service (`mint-admin-rest`) using Docker.

## Prerequisites
- Docker and Docker Compose installed
- Access to this repository and optionally the main mint repo for a full stack

## Build the Admin REST image (Jib)

Use Jib to build a container image directly from Maven:

```bash
cd mint-admin-rest
mvn -q -DskipTests jib:build
```

By default this publishes to your configured registry. To build to the local daemon instead, use `jib:dockerBuild`.

## Run with Docker Compose

If you are using the main mint repository’s Compose stack, it includes a service entry for the Admin REST image. From the mint repo:

```bash
cd ../cashu-mint
docker compose --profile dev up -d cashu-mint-admin-rest
```

Environment variables (with sensible defaults) control the admin port and token:

- `CASHU_MINT_ADMIN_PORT` (default `7778`)
- `CASHU_MINT_ADMIN_API_TOKEN` (default `local-dev-token`)

## Verify the service

Check readiness and the OpenAPI docs:

```bash
curl -fsS http://localhost:7778/actuator/health/readiness
curl -fsS http://localhost:7778/v3/api-docs | jq .info
```

## Call an admin endpoint

Admin endpoints are rooted at `/admin` (no `/v1`). Example: provision a mint

```bash
curl -X POST "http://localhost:7778/admin/lifecycle/mints" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Token: local-dev-token" \
  -H "X-Admin-Roles: MINT_ADMIN" \
  -d '{
        "mintId": "mint-001",
        "requestedBy": {"id": "ops", "displayName": "Ops Bot"},
        "metadata": {"displayName": "Primary mint"},
        "configuration": {"versionTag": "dev"}
      }'
```

For more how-to guides and references, see the links in this repository’s `docs/README.md`.

