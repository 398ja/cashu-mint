# Install a staging Cashu Mint with Docker

This how-to describes installing the Cashu Mint stack onto a staging host that only has Docker installed (no source code checkout). You will download the dev Docker Compose file (includes seed data and mock services), fetch the required helper files that the dev compose mounts, set environment values, pull images, and start the containers without building from source.

## Prerequisites
- Docker with the Compose plugin.
- Network access from the host to pull images from `docker.398ja.xyz`.
- Open ports on the host for the services you expose (defaults: mint `7777`, gateway `8080`, admin `7778`, webhook `9090`, vault `3333`, Phoenixd `9740`).

## Prepare the workspace on the staging host
1) Create a working directory on the staging host and change into it:

```bash
mkdir -p ~/cashu-mint-staging && cd ~/cashu-mint-staging
```

2) Download the dev Docker Compose file from the release tag that matches the version you want to run (avoid the `main` branch):

```bash
COMPOSE_VERSION=0.3.2
curl -L -o docker-compose.dev.yml \
  "https://raw.githubusercontent.com/cashubtc/cashu-mint/v${COMPOSE_VERSION}/docker-compose.dev.yml"
```

> If outbound network access is restricted, copy the `docker-compose.dev.yml` file into this directory via your approved channel (scp, config management, etc.).

3) Fetch the helper files that the dev compose mounts from the matching repository tags so the volume paths exist even without the source tree:

```bash
# Seed scripts expected by the compose file
mkdir -p scripts
curl -L -o scripts/preload-test-data.json \
  "https://raw.githubusercontent.com/cashubtc/cashu-mint/v${COMPOSE_VERSION}/scripts/preload-test-data.json"
curl -L -o scripts/preload-test-data.sql \
  "https://raw.githubusercontent.com/cashubtc/cashu-mint/v${COMPOSE_VERSION}/scripts/preload-test-data.sql"

# Vault migration expected at ../cashu-vault/... relative to the compose file location
mkdir -p ../cashu-vault/cashu-vault-jpa/src/main/resources/db/migration
curl -L -o ../cashu-vault/cashu-vault-jpa/src/main/resources/db/migration/V1__init_schema.sql \
  "https://raw.githubusercontent.com/cashubtc/cashu-vault/v${CASHU_VAULT_VERSION:-0.3.0}/cashu-vault-jpa/src/main/resources/db/migration/V1__init_schema.sql"
```

## Prepare a staging env file
Create `.env.staging` in the same directory with the versions and secrets you want to run. Adjust values for your host as needed.

```bash
cat > .env.staging <<'EOF'
# Versions (align with pom.xml)
CASHU_MINT_VERSION=0.3.2
CASHU_GATEWAY_VERSION=0.4.1
CASHU_GATEWAY_WEBHOOK_VERSION=latest
CASHU_VAULT_VERSION=0.3.0
CASHU_MINT_ADMIN_VERSION=0.2.4
COMPOSE_VERSION=0.3.2
PHOENIXD_VERSION=latest

# Ports and bindings
CASHU_MINT_PORT=7777
CASHU_GATEWAY_PORT=8080
CASHU_MINT_ADMIN_PORT=7778
CASHU_GATEWAY_WEBHOOK_PORT=9090
CASHU_VAULT_PORT=3333
PHOENIXD_PORT=9740
PHOENIXD_SERVICE=phoenixd

# Secrets
PHOENIXD_API_KEY=<your-staging-phoenixd-api-key>
PHOENIXD_API_TOKEN=<your-staging-phoenixd-api-token>
CASHU_MINT_ADMIN_API_TOKEN=<random-admin-token>
EOF
```

## Pull the images
Fetch the images ahead of time so `up` does not build locally and to confirm registry access:

```bash
docker compose -f docker-compose.dev.yml --env-file .env.staging pull \
  cashu-vault-jpa cashu-gateway-rest cashu-gateway-webhook cashu-mint-rest-dev cashu-mint-admin-rest
```

## Start the staging stack
Start the dev stack without building from source (`--no-build` ensures only pulled images are used):

```bash
docker compose -f docker-compose.dev.yml --env-file .env.staging up -d --no-build \
  cashu-vault-db cashu-gateway-db vault-db-init vault-db-seed cashu-vault-jpa \
  phoenixd-mock cashu-gateway-rest cashu-gateway-webhook cashu-mint-rest-dev cashu-mint-admin-rest
```

Health probes will wait for Postgres and applications to become ready. Data is persisted in the Docker volumes declared in the compose file (Postgres, Phoenixd).

## Verify the deployment
- Check container health:

```bash
docker compose -f docker-compose.dev.yml --env-file .env.staging ps
```

- Verify the mint API:

```bash
curl http://<staging-host>:${CASHU_MINT_PORT:-7777}/v1/info
```

- Verify admin API readiness:

```bash
curl -H "Authorization: Bearer ${CASHU_MINT_ADMIN_API_TOKEN}" \
  http://<staging-host>:${CASHU_MINT_ADMIN_PORT:-7778}/actuator/health/readiness
```

## Stop the stack
To stop containers while keeping volumes and data:

```bash
docker compose -f docker-compose.dev.yml --env-file .env.staging down
```

Append `--volumes` if you also want to remove the persisted databases.
