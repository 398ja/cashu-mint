# Cashu artifact dependencies

This reference maps the published Cashu artifacts and how they depend on each other. Use it to align versions or understand where to plug in new adapters.

## High-level view

```
cashu-lib-*     ┐
cashu-gateway-* ┼─ cashu-mint-protocol ─┬─ cashu-mint-rest ─┬─ cashu-mint-rest-it
cashu-vault-*   ┘                      │                   └─ cashu-mint-observability (auto-config)
cashu-voucher-* (voucher profile)      └─ cashu-mint-tools
                                          └─ (admin adapters published from cashu-mint-admin)
```

- **Foundation (`cashu-lib-*`).** Shared domain models, cryptographic helpers, and DTOs reused across the ecosystem.
- **Infrastructure (`cashu-vault-*`, `cashu-gateway-*`).** Storage and payment adapters consumed by the protocol layer.
- **Protocol and surfaces (`cashu-mint-*`).** The protocol module implements NUT workflows; the REST module exposes them over HTTP; observability adds metrics/health/tracing; tools generate preload fixtures; rest-it hosts integration tests.
- **Voucher support (`cashu-voucher-*`).** Voucher issuance and ledger abstractions pulled in when the voucher profile is active.
- **Admin surface.** Admin REST/CLI artifacts are published from the sibling `cashu-mint-admin` repository; this project references the released admin REST image in Docker Compose.

## Artifact catalogue

| Artifact | Depends on | Purpose |
| --- | --- | --- |
| `cashu-lib-common` | – | Core types, key utilities, and error classes shared across the ecosystem. |
| `cashu-lib-entities` | `cashu-lib-common` | Jackson-ready DTOs representing Cashu protocol requests and responses. |
| `cashu-lib-crypto` | `cashu-lib-common` | EC point, hash-to-curve, and signature helpers used by both mint and wallet implementations. |
| `cashu-vault-api` | `cashu-lib-*` | Vault SPI (`DBProofVault`, `DBMintVault`) decoupling persistence from protocol code. |
| `cashu-vault-jpa` | `cashu-vault-api` | Default JPA-backed implementation of the vault SPI, wiring repositories and entities. |
| `cashu-gateway-common` | `cashu-lib-*` | Gateway SPI describing how the mint talks to external payment processors. |
| `cashu-gateway-phoenixd` | `cashu-gateway-common` | Phoenixd Lightning implementation of the gateway SPI. |
| `cashu-gateway-dummy` | `cashu-gateway-common` | Deterministic mock gateway used for testing and demo environments. |
| `cashu-voucher-*` | `cashu-lib-*` | Voucher domain/app/nostr components for the optional voucher profile. |
| `cashu-mint-protocol` | `cashu-lib-*`, `cashu-vault-*`, `cashu-gateway-*`, `cashu-voucher-*` | Implements Cashu NUT workflows and exposes task/service APIs reused by REST and admin adapters. |
| `cashu-mint-rest` | `cashu-mint-protocol`, `cashu-lib-entities`, `cashu-mint-observability` | Spring Boot service that exposes the mint API and actuator endpoints. |
| `cashu-mint-observability` | `cashu-mint-protocol`, Micrometer/Actuator | Auto-configures metrics, health indicators, and optional tracing. |
| `cashu-mint-tools` | `cashu-lib-*` | CLI utilities for generating preload data (JSON/SQL) and deterministic fixtures. |
| `cashu-mint-rest-it` | `cashu-mint-rest`, `cashu-voucher-*` | Integration tests for the REST module (voucher profile, H2). |

## Version alignment

The root `pom.xml` pins ecosystem versions via properties:

- `${cashu-lib.version}` → `cashu-lib-common`, `cashu-lib-entities`, `cashu-lib-crypto`.
- `${cashu-vault.version}` → `cashu-vault-api`, `cashu-vault-jpa`.
- `${cashu-gateway.version}` → `cashu-gateway-common`, `cashu-gateway-phoenixd`, `cashu-gateway-dummy`.
- `${cashu-voucher.version}` → voucher domain/app/nostr modules.
- `${cashu-mint-admin.version}` → admin artifacts published from `cashu-mint-admin` (referenced by Docker Compose).

## Working with the dependencies

- Add a new gateway by implementing `cashu-gateway-common` interfaces and mapping it via `gateway.<method>[.<unit>]`.
- Extend the REST API by wiring new controllers in `cashu-mint-rest` while keeping business logic in `cashu-mint-protocol`.
- Generate fixtures or seed databases via `cashu-mint-tools` if you need deterministic keysets for tests or demos.
