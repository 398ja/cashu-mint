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

When startup completes, the mint API is available at `http://localhost:7777/v1`. Verify it by checking the mint information:

```bash
curl http://localhost:7777/v1/info
```

You should receive a JSON response with details about the mint.

### Gateway mapping in dev

The dev profile maps Bolt11 to the Phoenixd gateway, backed by the `phoenixd-mock` service. Override the mapping by exporting `GATEWAY_BOLT11_SAT` when starting compose, e.g.:

```bash
GATEWAY_BOLT11_SAT=xyz.tcheeric.payment.adapter.ln.dummy.DummyGateway \
  docker compose --profile dev up
```

Use the Dummy gateway for fully offline demos or leave the default Phoenixd mapping to exercise invoice flows against the mock service.

## Start the production profile
Point the gateway at a real Phoenixd backend and start the production profile:

```bash
PHOENIXD_SERVICE=phoenixd PHOENIXD_API_KEY=<your-key> docker compose --profile prod up
```

Once the containers are healthy, the mint API is again accessible at `http://localhost:7777/v1`, now backed by a real Phoenixd instance.

## Stop the services
Use the following command to stop and remove the containers:

```bash
docker compose down
```

## Admin REST

Administrative services live in the separate admin project. See the admin documentation for how to build and run the Admin REST service:

- `../cashu-mint-admin/docs/tutorials/run-admin-rest-with-docker.md`
