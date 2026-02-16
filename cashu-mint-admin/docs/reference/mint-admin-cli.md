# Mint Admin CLI Reference

This reference summarizes the admin CLI commands, payloads, and output renderers. It assumes you built the CLI:

```
mvn -q -pl mint-admin-cli -am -DskipTests package
```

Run the CLI:

```
java -jar mint-admin-cli/target/mint-admin-cli-<version>-runner.jar mint --help
```

## Commands

- `mint status` — show current status for a mint (operators, active config, incident summary)
- `mint config` — apply configuration overrides and show the resulting revision metadata
- `mint users` — list provisioned operators; support `--include-inactive`
- `mint alerts` — list active alerts; support filters and quiet mode
- `mint create|update|pause|resume|retire` — lifecycle subcommands that modify the mint state

Common options:
- `--input-format {JSON|YAML}` — payload format (default JSON)
- `--output-format {TABLE|JSON}` — render format (default TABLE)
- `--file <path>` — read payload from a file instead of stdin
- `--mint-id <uuid>` — target mint id (defaults from config when omitted)
- `--operator-id <uuid>` — operator performing the action
- `--correlation-id <uuid>` — request correlation id (auto-generated when omitted)

## Payloads (schemas)

Lifecycle (create/update/pause/resume/retire) share a base:

```json
{
  "mintId": "<uuid>",
  "operatorId": "<uuid>",
  "version": "v1",
  "requestId": "<uuid>",
  "correlationId": "<uuid>",
  "metadata": { "note": "optional context" }
}
```

Config (apply overrides):

```json
{
  "mintId": "<uuid>",
  "operatorId": "<uuid>",
  "overrides": {
    "currency": "sat",
    "fees": { "melt": { "ppt": 4, "fixed": 1 } },
    "limits": { "maxMint": 100000 }
  }
}
```

Status requires no payload by default; use `--mint-id` to target a specific mint.

## Examples

Status (JSON):

```
java -jar mint-admin-cli-<ver>-runner.jar mint status --output-format JSON
```

Create mint (from file):

```
java -jar mint-admin-cli-<ver>-runner.jar mint create --file payloads/create.json
```

Update config (YAML):

```
java -jar mint-admin-cli-<ver>-runner.jar mint config --input-format YAML --file payloads/config.yaml
```

## Output renderers

- TABLE — human-friendly tabular output for quick inspection
- JSON — machine-readable output for scripting/automation

