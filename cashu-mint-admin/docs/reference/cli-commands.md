# CLI command reference

The admin CLI is built with Picocli and packaged as a shaded JAR.

```bash
java -jar mint-admin-cli/target/mint-admin-cli-*-runner.jar [OPTIONS] COMMAND
```

## Global options

| Option | Description |
|--------|-------------|
| `--api-url <url>` | REST API base URL (enables HTTP mode; omit to use stubs) |
| `--api-key <key>` | API authentication token |
| `--help` | Show usage help |

## I/O options

Available on all subcommands:

| Option | Default | Description |
|--------|---------|-------------|
| `-f, --input-format <fmt>` | `JSON` | Payload format |
| `-p, --payload <string>` | — | Inline payload |
| `-F, --payload-file <path>` | — | Path to payload file |
| `-o, --output-format <fmt>` | `TABLE` | Output format (`TABLE` or `JSON`) |

## `mint`

Inspect the state of a mint. With no subcommand, prints the current mint status.

```bash
java -jar mint-admin-cli-*-runner.jar mint
```

## `mint create`

Provision a new mint with the supplied configuration.

| Option | Description |
|--------|-------------|
| `--mint-id <id>` | Identifier of the mint to create |
| `--operator-id <uuid>` | Operator UUID authorizing the action |
| `--version-tag <tag>` | Version tag recorded with the change |
| `--request-id <uuid>` | Unique invocation identifier |
| `--correlation-id <id>` | External correlation identifier |
| `-y, --yes` | Skip confirmation prompt |
| `--dry-run` | Validate without executing |

Target state: `PROVISIONED`

## `mint update`

Apply a configuration update to an existing mint. Same options as `mint create`.

## `mint pause`

Suspend mint operations until explicitly resumed. Same options as `mint create`.

Target state: `SUSPENDED`

## `mint resume`

Reactivate a paused mint. Same options as `mint create`.

Target state: `ACTIVE`

## `mint retire`

Decommission a mint and mark it as retired. Same options as `mint create`.

Target state: `DECOMMISSIONED`

## `mint config`

Inspect or apply mint configuration.

| Option | Default | Description |
|--------|---------|-------------|
| `-m, --mint-id <id>` | default mint | Mint identifier |

Accepts configuration payload via `-p` or `-F`.

## `mint users`

Inspect operator accounts.

| Option | Default | Description |
|--------|---------|-------------|
| `-m, --mint-id <id>` | default mint | Mint identifier |
| `--include-inactive` | `false` | Include inactive operators |

## `mint alerts`

Inspect mint alerts.

| Option | Default | Description |
|--------|---------|-------------|
| `-m, --mint-id <id>` | default mint | Mint identifier |
| `-s, --severity <level>` | default | Minimum severity filter |

## See also

- [Administer a mint from the CLI](../tutorials/administer-mint-from-cli.md) — tutorial walkthrough
- [REST API reference](rest-api.md) — endpoints the CLI calls
