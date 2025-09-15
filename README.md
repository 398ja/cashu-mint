# cashu-mint

cashu-mint is a Java implementation of the [Cashu protocol](https://github.com/cashubtc/nuts) providing a core library and REST API for running a mint.

The mint now exposes a shared `SignatureVaultService` bean so that signatures minted in one request can be restored in a later request. When calling protocol methods such as `NUT04.mint` or `NUT09.restore` directly, pass the `SignatureVaultService` instance to ensure signatures persist across calls.

## Modules

- `cashu-mint-protocol` – core library for the Cashu protocol.
- `cashu-mint-rest` – REST API for running a mint.
- `cashu-mint-admin` – administrative module (see `cashu-mint-admin/project/specification.md`).

## Documentation

Documentation following the [Diátaxis](https://diataxis.fr/) framework is available in [docs](docs/README.md).

- Gateway configuration (method/unit mappings): see docs/how-to/configure-gateways.md.
