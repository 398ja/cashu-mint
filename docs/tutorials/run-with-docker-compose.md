# Run with Docker Compose

This tutorial walks you through starting the Cashu Mint stack using Docker Compose.

## Prerequisites
- Docker
- Docker Compose

## Start the development profile
Run the following command to start all services with the development profile:

```bash
docker compose --profile dev up
```

When startup completes, the mint API is available at `http://localhost:7777`. Verify it by checking the mint information:

```bash
curl http://localhost:7777/info
```

You should receive a JSON response with details about the mint.

### Gateway mapping in dev

The dev profile maps Bolt11 to the Dummy gateway class by default. Ensure your `cashu-gateway` Dummy implementation supports `BOLT11`.

- Default (no action needed): dev sets `GATEWAY_BOLT11_SAT` and `GATEWAY_BOLT11` to `xyz.tcheeric.gateway.dummy.DummyGateway`.
- Override in dev: point to a different gateway by exporting `GATEWAY_BOLT11_SAT` when starting compose, e.g.:

```bash
GATEWAY_BOLT11_SAT=xyz.tcheeric.gateway.phoenixd.PhoenixdGateway \
  docker compose --profile dev up
```

Note: If your current Dummy gateway does not support Bolt11 yet, update `cashu-gateway` accordingly (see how-to: Configure gateways). This only affects the dev stack. Production continues to use Phoenixd via properties unless you override.

## Start the production profile
Set the Phoenixd service to the real backend and start the production profile:

```bash
PHOENIXD_SERVICE=phoenixd-rest PHOENIXD_API_KEY=<your-key> docker compose --profile prod up
```

Once the containers are healthy, the mint API is again accessible at `http://localhost:7777`, now backed by a real Phoenixd instance.

## Stop the services
Use the following command to stop and remove the containers:

```bash
docker compose down
```

## Admin REST (separate project)

The admin services were moved to a separate project at `../cashu-mint-admin` with three modules: `mint-admin-core`, `mint-admin-cli`, and `mint-admin-rest`.

- Docker image: `docker.398ja.xyz/cashu-mint-admin-rest:${CASHU_MINT_ADMIN_VERSION:-latest}`.
- This Compose file pulls the published image; to build it locally:
  1. `cd ../cashu-mint-admin/mint-admin-rest`
  2. `mvn -q -DskipTests jib:build`
- Then (re)start the stack: `docker compose --profile dev up -d cashu-mint-admin-rest`.
