# Cashu artifact dependencies

This reference maps the published Cashu artifacts and shows how they depend on each other.
Use it when you need to align versions or understand which library provides a given
capability before wiring new integrations.

## High-level view

```
cashu-lib-*  ─┐
              ├─ cashu-mint-tools
              ├─ cashu-mint-protocol ─┬─ cashu-mint-rest
cashu-vault-* ┤                      └─ (admin adapters in cashu-mint-admin)
cashu-gateway-* ┘
```

- **Foundation (`cashu-lib-*`).** Shared domain models, cryptographic helpers, and
  transport DTOs that every other module builds on.
- **Infrastructure (`cashu-vault-*`, `cashu-gateway-*`).** Pluggable storage and
  payment adapters consumed by the protocol layer.
- **Mint surfaces (`cashu-mint-*`).** The protocol module encapsulates Cashu NUT
  workflows, the REST module exposes them over HTTP, and the tools module ships
  developer utilities for seeding and diagnostics.

## Artifact catalogue

| Artifact | Depends on | Purpose |
| --- | --- | --- |
| `cashu-lib-common` | – | Core types, key utilities, and error classes shared across the ecosystem. |
| `cashu-lib-entities` | `cashu-lib-common` | Jackson-ready DTOs representing Cashu protocol requests and responses. |
| `cashu-lib-crypto` | `cashu-lib-common` | EC point, hash-to-curve, and signature helpers used by both mint and wallet implementations. |
| `cashu-vault-api` | `cashu-lib-*` | Abstract vault SPI (`DBProofVault`, `DBMintVault`) decoupling persistence from protocol code. |
| `cashu-vault-jpa` | `cashu-vault-api` | Default JPA-backed implementation of the vault SPI, wiring repositories and entities. |
| `cashu-gateway-common` | `cashu-lib-*` | Gateway SPI describing how the mint talks to external payment processors. |
| `cashu-gateway-phoenixd` | `cashu-gateway-common` | Phoenixd lightning implementation of the gateway SPI. |
| `cashu-gateway-dummy` | `cashu-gateway-common` | Deterministic mock gateway used for testing and demo environments. |
| `cashu-mint-protocol` | `cashu-lib-*`, `cashu-vault-*`, `cashu-gateway-*` | Implements Cashu NUT workflows and exposes task/service APIs reused by REST, CLI, and admin adapters. |
| `cashu-mint-rest` | `cashu-mint-protocol`, `cashu-lib-entities` | Spring Boot service that exposes the mint API; embeds the protocol module and delegates to its services. |
| `cashu-mint-tools` | `cashu-lib-*` | CLI utilities for generating preload data (JSON/SQL) and deterministic fixtures. |
| `cashu-mint-admin`<sup>1</sup> | `cashu-mint-protocol`, `cashu-lib-*`, `cashu-vault-*` | Administrative domain, REST, and CLI adapters maintained in the sister repository `cashu-mint-admin`. |

<sup>1</sup> The admin modules are published from the `cashu-mint-admin` repository but
share the same versioning scheme and protocol contracts.

## Version alignment

The root [`pom.xml`](../../pom.xml) pins the ecosystem to compatible releases
via the following properties:

- `${cashu-libs.version}` → `cashu-lib-common`, `cashu-lib-entities`, and `cashu-lib-crypto`.
- `${cashu-vaults.version}` → `cashu-vault-api` and `cashu-vault-jpa`.
- `${cashu-gateway.version}` → `cashu-gateway-common`, `cashu-gateway-phoenixd`, and `cashu-gateway-dummy`.

When you upgrade any of these families, bump the property once at the parent
POM and rebuild; Maven cascades the new version to every module.

## Working with the dependencies

- **Adding a new gateway or vault**: depend on `cashu-gateway-common` or
  `cashu-vault-api`, implement the SPI, then register the implementation in the
  protocol module.
- **Extending the REST API**: wire new routes in `cashu-mint-rest`, keeping the
  logic in `cashu-mint-protocol` so other adapters can reuse it.
- **Authoring tooling**: leverage `cashu-mint-tools` as a template. It pulls only
  the lightweight `cashu-lib-*` dependencies to keep distribution small.

Refer back to this table whenever you need to understand which module introduces
which external contract or when you evaluate the impact of a version upgrade across
the Cashu ecosystem.
