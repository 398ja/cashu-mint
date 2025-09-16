# Manage the mint lifecycle from the CLI

This guide walks operators through the `mint` admin CLI commands that create, update, pause, resume, and retire mint instances against the REST backend.

## Prerequisites

- A Cashu mint REST service running with administrative endpoints enabled.
- The CLI wired to real HTTP adapters as described in [Connect the admin CLI to the REST service](connect-admin-cli-to-rest.md).
- An administrator token with the `MINT_ADMIN` role and a UUID for the operator initiating changes.

## 1. Prepare a lifecycle payload

Lifecycle subcommands reuse the [`MintLifecycleRequest`](../../cashu-mint-admin-cli/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/model/MintLifecycleRequest.java) model, which requires the mint identifier, the operator UUID, and a version tag. Optional request and correlation identifiers will be generated automatically when omitted. Store the values in a JSON file so the same payload can drive multiple actions:

```json
{
  "mintId": "mint-001",
  "operatorId": "3c5a0d29-52f5-4dc4-9efc-9d2fba7d4742",
  "versionTag": "2024-Q2",
  "correlationId": "maintenance-window-42"
}
```

Passing the payload file with `--payload` keeps commands reproducible and avoids exposing secrets in process arguments.

## 2. Provision a mint

Run the `create` subcommand to provision the mint. The `-y` flag skips the interactive confirmation, and `--output-format table` mirrors the CLI defaults:

```bash
mint create \
  --payload lifecycle/mint-001.json \
  --input-format json \
  --output-format table \
  --yes
```

A successful run prints the lifecycle summary assembled by [`MintCreateCommand`](../../cashu-mint-admin-cli/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/command/MintCreateCommand.java), including the new state and version tag.

## 3. Apply lifecycle updates

Use the same payload to roll forward configuration bindings or metadata once the backend exposes a new revision tag:

```bash
mint update \
  --payload lifecycle/mint-001.json \
  --input-format json \
  --output-format json \
  --yes
```

Switching to JSON output provides a machine-readable [`LifecycleSummary`](../../cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/presentation/lifecycle/LifecycleSummary.java) that can be captured by automation.

## 4. Pause and resume for maintenance

Invoke the `pause` command before maintenance windows, then `resume` once checks pass. When you omit `--yes`, the CLI prompts for confirmation to avoid accidental downtime:

```bash
mint pause --payload lifecycle/mint-001.json --input-format json
mint resume --payload lifecycle/mint-001.json --input-format json --yes
```

Both commands execute through the shared [`MintLifecycleCommandSupport`](../../cashu-mint-admin-cli/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/command/MintLifecycleCommandSupport.java), which adds correlation identifiers and renders the summary with [`LifecycleSummaryCliPresenter`](../../cashu-mint-admin-cli/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/presentation/lifecycle/LifecycleSummaryCliPresenter.java).

## 5. Retire a mint

When decommissioning an instance, run the `retire` subcommand with the same payload:

```bash
mint retire --payload lifecycle/mint-001.json --input-format json --yes
```

If the backend reports that the mint is already retired, the CLI prints the summary and notifies you that the request was idempotent, ensuring repeatable automation.
