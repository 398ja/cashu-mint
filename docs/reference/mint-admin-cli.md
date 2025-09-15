# Mint admin CLI reference

The mint admin CLI is a Picocli application that orchestrates lifecycle, configuration, user, and alert workflows through a
single `mint` command tree. The default launcher wires stub implementations of each port so operators can explore the interface
without external dependencies before swapping in real adapters.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/MintAdminCliApplication.java†L34-L59】

## Command overview

| Command | Purpose | Key options | Output |
| --- | --- | --- | --- |
| `mint` | Display the current lifecycle state and high-level health of a mint instance.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/command/MintCommand.java†L17-L48】 | `--payload` / `--payload-file` to override the default `mintId` (`default-mint`).【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/model/MintStatusRequest.java†L3-L13】 | Single `MintStatusResponse` rendered as a table or JSON.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/model/MintStatusResponse.java†L3-L7】 |
| `mint config` | Apply or preview configuration parameters and revisions for a mint.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/command/MintConfigCommand.java†L18-L53】 | `--mint-id` to target another instance when no payload is provided; payload supports merging arbitrary key/value overrides.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/model/MintConfigRequest.java†L6-L17】 | `MintConfigResponse` showing the revision tag and resulting parameters.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/model/MintConfigResponse.java†L6-L13】 |
| `mint users` | Inspect operator accounts, including inactive members when needed.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/command/MintUsersCommand.java†L19-L59】 | `--mint-id` for scope and `--include-inactive` to broaden the result set.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/command/MintUsersCommand.java†L31-L39】 | A list of `MintUserRecord` entries rendered in the chosen format.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/model/MintUserRecord.java†L1-L12】 |
| `mint alerts` | Review or filter operational alerts by severity.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/command/MintAlertsCommand.java†L19-L59】 | `--mint-id` and `--severity` determine the alert scope when no payload is supplied.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/model/MintAlertsRequest.java†L3-L11】 | A list of `MintAlertRecord` objects for the requested severity threshold.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/model/MintAlertRecord.java†L1-L12】 |

## Shared I/O options

Every command mixes in `CommandIOOptions`, which adds consistent flags for payload and rendering control. Inline payloads and
file-based payloads are mutually exclusive, and the CLI accepts JSON or YAML via `--input-format`. The `--output-format` flag
switches between table and JSON renderers.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/command/CommandIOOptions.java†L17-L61】【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/io/InputFormat.java†L3-L9】【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/io/OutputFormat.java†L3-L9】

Payloads are decoded with the shared `CommandPayloadMapper`, which already registers Jackson modules for JSON and YAML parsing.
Responses flow through the `ResponseRenderingService`, which ships with both JSON and ASCII table implementations out of the
box.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/io/CommandPayloadMapper.java†L22-L49】【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/io/ResponseRenderingService.java†L25-L45】【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/io/JsonResponseRenderer.java†L9-L30】【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/io/TableResponseRenderer.java†L14-L144】

## Default stub data

Out of the box the CLI uses deterministic stub ports so you can try every command offline. These ports return hard-coded status
snapshots, configuration maps, operator rosters, and alert lists until you replace them with real adapters.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/MintAdminCliApplication.java†L34-L59】【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/port/stub/StubMintStatusPort.java†L12-L19】【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/port/stub/StubMintConfigPort.java†L17-L33】【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/port/stub/StubMintUsersPort.java†L15-L27】【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/port/stub/StubMintAlertsPort.java†L17-L36】

When you are ready to connect to the REST API, follow the dedicated how-to in the How-to guides section.
