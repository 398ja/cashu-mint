# Install a staging Cashu Mint with Docker

This how-to describes installing the Cashu Mint stack onto a staging host with only Docker installed. You will download the release archive (to supply Dockerfiles and scripts referenced by Compose), set environment values, pull images, and start the containers without building from source.

## Prerequisites
- Docker with the Compose plugin.
- Network access from the host to pull images from `docker.398ja.xyz`.
- Open ports on the host for the services you expose (defaults: mint `7777`, gateway `8080`, admin `7778`, webhook `9090`, vault `3333`, Phoenixd `9740`).

## Prepare the workspace on the staging host
1) Create a working directory on the staging host and change into it:

```bash
mkdir -p ~/cashu-mint-staging && cd ~/cashu-mint-staging
```

2) Download the release archive for the version you want to run (provides the Compose file, Dockerfiles, and helper scripts required by the build contexts):

```bash
COMPOSE_VERSION=0.4.1
curl -L "https://github.com/cashubtc/cashu-mint/archive/refs/tags/v${COMPOSE_VERSION}.tar.gz" -o cashu-mint.tar.gz
tar -xzf cashu-mint.tar.gz --strip-components=1
```

3) Fetch the vault migration expected by the dev Compose file (relative path `../cashu-vault/...`):

```bash
mkdir -p ../cashu-vault/cashu-vault-jpa/src/main/resources/db/migration
curl -L -o ../cashu-vault/cashu-vault-jpa/src/main/resources/db/migration/V1__init_schema.sql \
  "https://raw.githubusercontent.com/cashubtc/cashu-vault/v0.4.0/cashu-vault-jpa/src/main/resources/db/migration/V1__init_schema.sql"
```

## Prepare a staging env file
Create `.env.staging` in the same directory with the versions and secrets you want to run. Adjust values for your host as needed.

```bash
cat > .env.staging <<'EOF'
# Versions (align with pom.xml)
CASHU_MINT_VERSION=0.4.1
CASHU_GATEWAY_VERSION=0.4.2
CASHU_GATEWAY_WEBHOOK_VERSION=latest
CASHU_VAULT_VERSION=0.4.0
CASHU_MINT_ADMIN_VERSION=0.2.4
COMPOSE_VERSION=0.4.1
PHOENIXD_VERSION=0.1.4

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
  cashu-vault-jpa cashu-gateway-rest cashu-gateway-webhook cashu-mint-rest-dev cashu-mint-admin-rest phoenixd-mock
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
