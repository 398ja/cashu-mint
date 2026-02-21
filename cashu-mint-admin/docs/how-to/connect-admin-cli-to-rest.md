# Connect the admin CLI to the REST service

This guide shows how to replace the CLI's built-in stub adapters with HTTP adapters that call the admin REST API.

## Prerequisites

- The admin REST service running (default port 7778)
- The CLI built: `mvn -q -pl mint-admin-cli -am -DskipTests package`
- A valid API token (default: `local-dev-token` in dev mode)

## 1. Start the admin REST service

If using the dev docker compose stack:

```bash
docker compose -f docker-compose.dev.yml up -d cashu-mint-admin-rest
```

Or run directly:

```bash
cd mint-admin-rest
mvn -q -DskipTests spring-boot:run
```

Verify it is up:

```bash
curl -s http://localhost:7778/actuator/health | jq .
```

## 2. Pass connection options to the CLI

The `--api-url` and `--api-key` options switch the CLI from stub mode to HTTP mode:

```bash
java -jar mint-admin-cli/target/mint-admin-cli-*-runner.jar \
  --api-url http://localhost:7778 \
  --api-key local-dev-token \
  mint
```

When `--api-url` is provided, every CLI command sends requests to the REST service instead of using the in-memory stubs.

## 3. Common operations over HTTP

```bash
ADMIN_CLI="java -jar mint-admin-cli/target/mint-admin-cli-*-runner.jar \
  --api-url http://localhost:7778 --api-key local-dev-token"

# Check mint status
$ADMIN_CLI mint

# Provision a mint
$ADMIN_CLI mint create --mint-id prod-mint \
  --operator-id 550e8400-e29b-41d4-a716-446655440000 \
  --version-tag v1.0.0

# Inspect configuration
$ADMIN_CLI mint config --mint-id prod-mint

# List alerts as JSON
$ADMIN_CLI mint alerts --mint-id prod-mint -o JSON
```

## 4. Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Connection refused` | REST service not running | Start it with docker compose or `spring-boot:run` |
| `401 Unauthorized` | Invalid API token | Check `ADMIN_API_TOKEN` on the server matches `--api-key` |
| `404 Not Found` | Wrong base URL | Ensure `--api-url` points to the admin service, not the mint |

## See also

- [Administer a mint from the CLI](../tutorials/administer-mint-from-cli.md) — tutorial walkthrough
- [CLI command reference](../reference/cli-commands.md) — full option reference
- [REST API reference](../reference/rest-api.md) — endpoint documentation
