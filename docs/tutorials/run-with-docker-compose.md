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
