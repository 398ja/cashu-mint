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
COMPOSE_VERSION=0.32.0
curl -L "https://github.com/cashubtc/cashu-mint/archive/refs/tags/v${COMPOSE_VERSION}.tar.gz" -o cashu-mint.tar.gz
tar -xzf cashu-mint.tar.gz --strip-components=1
```

The vault migrates its own schema with Flyway at startup, so no migration file
needs to be fetched. (An earlier `vault-db-init` service applied `V1` by hand and
re-created a constraint that made key rotation impossible; it has been removed.)

## Prepare a staging env file
Generate the mint's operator credential first. The mint holds a bcrypt hash and
Prometheus scrapes with the plain text, so both come from one script to keep
them in step:

```bash
scripts/mint-admin-password.sh ~/cashu-mint-scrape-password
```

It writes the plain text to `~/cashu-mint-scrape-password` (mode 600) and
prints the `MINT_ADMIN_PASSWORD=` line to paste below, with every `$` already
doubled for compose.

Create `.env.staging` in the same directory with the versions and secrets you want to run. Adjust values for your host as needed.

```bash
cat > .env.staging <<'EOF'
# Versions (align with pom.xml)
CASHU_MINT_VERSION=0.32.0
CASHU_GATEWAY_VERSION=0.13.0
CASHU_GATEWAY_WEBHOOK_VERSION=0.13.0
CASHU_VAULT_VERSION=0.10.1
CASHU_MINT_ADMIN_VERSION=0.32.0
COMPOSE_VERSION=0.32.0
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
CASHU_MINT_ADMIN_SUPER_ADMIN_NPUB=<npub of the Super Administrator>
# Operator credential for /admin/** and the actuator metrics Prometheus scrapes.
# Paste the line printed by scripts/mint-admin-password.sh.
MINT_ADMIN_PASSWORD={bcrypt}$$2b$$10$$<rest-of-hash>
EOF
```

## Pull the images
Fetch the images ahead of time so `up` does not build locally and to confirm registry access:

```bash
docker compose -f docker-compose.dev.yml --env-file .env.staging pull \
  cashu-vault-jpa payment-adapter-rest payment-adapter-webhook cashu-mint-rest-dev cashu-mint-admin-rest phoenixd-mock
```

## Start the staging stack
Start the dev stack without building from source (`--no-build` ensures only pulled images are used):

```bash
docker compose -f docker-compose.dev.yml --env-file .env.staging up -d --no-build \
  cashu-vault-db payment-adapter-db cashu-vault-jpa \
  phoenixd-mock payment-adapter-rest payment-adapter-webhook cashu-mint-rest-dev cashu-mint-admin-rest
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
curl http://<staging-host>:${CASHU_MINT_ADMIN_PORT:-7778}/actuator/health/readiness
```

## Start the observability stack
Prometheus scrapes the mint's management port with the operator credential, so
it reads the plain-text file `scripts/mint-admin-password.sh` wrote earlier.
Start the stack with a staging targets directory:

```bash
mkdir -p cashu-mint-observability/docker/prometheus/targets.staging
cat > cashu-mint-observability/docker/prometheus/targets.staging/cashu-mint.yml <<'EOF'
- targets:
    - 'cashu-mint-rest-dev:9000'
  labels:
    env: 'staging'
    service: 'cashu-mint'
EOF

cd cashu-mint-observability/docker
CASHU_PROMETHEUS_TARGETS_DIR=./prometheus/targets.staging \
CASHU_MINT_SCRAPE_PASSWORD_FILE=~/cashu-mint-scrape-password \
  docker compose -f docker-compose.observability.yml up -d
cd -
```

Confirm the scrape is healthy before opening Grafana. `health` must be `up`
and `lastError` empty; a `401 Unauthorized` there means the scrape password
does not match the mint's `MINT_ADMIN_PASSWORD`:

```bash
curl -s localhost:9090/api/v1/targets | jq '.data.activeTargets[] | select(.labels.job=="cashu-mint") | {health, lastError}'
```

See [Enable observability](enable-observability.md) for the rest of the stack.

## Stop the stack
To stop containers while keeping volumes and data:

```bash
docker compose -f docker-compose.dev.yml --env-file .env.staging down
```

Append `--volumes` if you also want to remove the persisted databases.
