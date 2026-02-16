# Administer a mint from the CLI

This tutorial walks through the mint admin command-line interface (CLI) to practice inspecting status, reviewing configuration, managing users, and triaging alerts.

## Prerequisites

- Java 21 with `JAVA_HOME` set
- The admin module built: `mvn -q -pl mint-admin-cli -am -DskipTests package`

## 1. Build and run the CLI

```bash
mvn -q -pl mint-admin-cli -am -DskipTests package
java -jar mint-admin-cli/target/mint-admin-cli-*-runner.jar --help
```

The CLI ships with stub adapters by default, so you can explore commands without a running backend.

## 2. Check mint status

```bash
java -jar mint-admin-cli/target/mint-admin-cli-*-runner.jar mint
```

This prints the current mint status in table format. To get JSON output:

```bash
java -jar mint-admin-cli/target/mint-admin-cli-*-runner.jar mint -o JSON
```

## 3. Provision a new mint

Create a mint by supplying a payload with the configuration:

```bash
java -jar mint-admin-cli/target/mint-admin-cli-*-runner.jar mint create \
  --mint-id my-mint \
  --operator-id 550e8400-e29b-41d4-a716-446655440000 \
  --version-tag v1.0.0 \
  --dry-run
```

The `--dry-run` flag validates and shows what would happen without executing. Remove it to provision for real. The CLI will prompt for confirmation unless you pass `-y`.

## 4. Manage the lifecycle

Pause, resume, and retire follow the same pattern:

```bash
# Pause a mint for maintenance
java -jar mint-admin-cli/target/mint-admin-cli-*-runner.jar mint pause \
  --mint-id my-mint \
  --operator-id 550e8400-e29b-41d4-a716-446655440000 \
  --version-tag v1.0.1 -y

# Resume after maintenance
java -jar mint-admin-cli/target/mint-admin-cli-*-runner.jar mint resume \
  --mint-id my-mint \
  --operator-id 550e8400-e29b-41d4-a716-446655440000 \
  --version-tag v1.0.1 -y
```

## 5. Inspect configuration

```bash
java -jar mint-admin-cli/target/mint-admin-cli-*-runner.jar mint config \
  --mint-id my-mint
```

You can also supply configuration changes from a file:

```bash
java -jar mint-admin-cli/target/mint-admin-cli-*-runner.jar mint config \
  --mint-id my-mint \
  -F config-update.json
```

## 6. List users

```bash
java -jar mint-admin-cli/target/mint-admin-cli-*-runner.jar mint users \
  --mint-id my-mint

# Include inactive users
java -jar mint-admin-cli/target/mint-admin-cli-*-runner.jar mint users \
  --mint-id my-mint --include-inactive
```

## 7. Triage alerts

```bash
# List all alerts
java -jar mint-admin-cli/target/mint-admin-cli-*-runner.jar mint alerts \
  --mint-id my-mint

# Filter by severity
java -jar mint-admin-cli/target/mint-admin-cli-*-runner.jar mint alerts \
  --mint-id my-mint -s CRITICAL
```

## 8. Connect to a live backend

Once the admin REST service is running, point the CLI at it:

```bash
java -jar mint-admin-cli/target/mint-admin-cli-*-runner.jar \
  --api-url http://localhost:7778 \
  --api-key local-dev-token \
  mint
```

See [Connect the CLI to the REST service](../how-to/connect-admin-cli-to-rest.md) for details.

## Next steps

- [CLI command reference](../reference/cli-commands.md) — full option reference
- [REST API reference](../reference/rest-api.md) — endpoints the CLI calls
- [Configure persistence](../how-to/configure-mint-admin-persistence.md) — database setup for the REST backend
