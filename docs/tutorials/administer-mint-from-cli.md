# Administer a mint from the CLI

This tutorial walks through the Mint admin command-line interface (CLI) so that new operators can practice inspecting status,
reviewing configuration, and triaging alerts before wiring the tool to a live backend. The default launcher wires the CLI to
deterministic stub ports, letting you explore commands without deploying any other services yet.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/MintAdminCliApplication.java†L34-L43】

## Prerequisites

* Java 21 and Maven installed locally.
* The Cashu mint sources cloned on your machine.

## 1. Build the CLI artifact

From the repository root, package the admin module. Skipping tests keeps the feedback loop fast while you learn the command set:

```bash
./mvnw -pl cashu-mint-admin -am -DskipTests package
```

Maven emits a runnable JAR under `cashu-mint-admin/target/`. Use the current module version in the filename (for example, run
`./mvnw -pl cashu-mint-admin help:evaluate -Dexpression=project.version -q -DforceStdout` to print it).

## 2. Inspect overall mint status

Invoke the top-level `mint` command without any payload to request the default snapshot. The CLI automatically constructs the
request with the built-in mint identifier when none is provided.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/command/MintCommand.java†L41-L47】【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/model/MintStatusRequest.java†L3-L13】

```bash
java -jar cashu-mint-admin/target/cashu-mint-admin-<version>.jar mint
```

Sample output rendered by the table formatter:

```
+--------------+--------+--------------+---------------+
| mintId       | state  | activeUsers  | pendingAlerts |
+--------------+--------+--------------+---------------+
| default-mint | ACTIVE | 4            | 1             |
+--------------+--------+--------------+---------------+
```

These values come from the stub status port, which always reports an active mint with four operators and one alert so that you
can recognize the layout before connecting to a real service.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/port/stub/StubMintStatusPort.java†L12-L19】

## 3. Preview configuration changes

Switch to the `config` subcommand to merge overrides into the synthetic baseline. When no payload is supplied, the CLI targets
the default mint ID; otherwise it parses the payload according to the selected input format.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/command/MintConfigCommand.java†L30-L53】【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/command/CommandIOOptions.java†L17-L45】

```bash
java -jar cashu-mint-admin/target/cashu-mint-admin-<version>.jar mint config \
  --payload '{"mintId":"default-mint","parameters":{"maxTokens":"2000"}}'
```

The stub configuration port merges your overrides with the default currency and returns a synthetic revision marker, letting you
see how revisions will look once persistence is implemented.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/port/stub/StubMintConfigPort.java†L17-L33】 Add `--output-format JSON` when you want the full payload instead of the table view.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/command/CommandIOOptions.java†L30-L41】【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/io/JsonResponseRenderer.java†L9-L30】

## 4. Review operator accounts

List provisioned operators with the `users` subcommand. Toggle the `--include-inactive` flag to reveal operators who cannot log
in right now.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/command/MintUsersCommand.java†L31-L59】

```bash
java -jar cashu-mint-admin/target/cashu-mint-admin-<version>.jar mint users --include-inactive
```

Because the stub user port ships with three sample records, you can verify how active and inactive accounts appear in both table
and JSON renderings before you connect to the live roster service.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/port/stub/StubMintUsersPort.java†L15-L27】

## 5. Triage alerts by severity

Use the `alerts` subcommand to practice filtering operational incidents. Provide the `--severity` option when you only want
warnings and errors; omit it to see everything.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/command/MintAlertsCommand.java†L31-L59】

```bash
java -jar cashu-mint-admin/target/cashu-mint-admin-<version>.jar mint alerts --severity WARN
```

The stub alert port ships with info, warning, and error examples so you can confirm that the filter behaves as expected.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/port/stub/StubMintAlertsPort.java†L17-L36】 Combine the flag with
`--output-format JSON` to feed alert data into other scripts.

## 6. Work with different payload sources

Every command supports inline payloads (`--payload`), file-based payloads (`--payload-file`), and JSON or YAML decoding controlled
by `--input-format`. The CLI enforces that you pick at most one payload source, then deserializes with a mapper that already
knows how to read either format.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/command/CommandIOOptions.java†L22-L61】【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/io/CommandPayloadMapper.java†L22-L49】

Example YAML workflow:

1. Save a file named `revision.yaml` with the content:
   ```yaml
   mintId: default-mint
   parameters:
     currency: sat
     maxTokens: "3000"
   ```
2. Run:
   ```bash
   java -jar cashu-mint-admin/target/cashu-mint-admin-<version>.jar mint config \
     --input-format YAML --payload-file revision.yaml --output-format JSON
   ```

## Next steps

Once you are comfortable with the command set, wire the CLI to the REST API by replacing the stub ports with adapters that call
the administrative endpoints. Follow the [Connect the admin CLI to the REST service how-to](../how-to/connect-admin-cli-to-rest.md)
for a step-by-step walkthrough.
