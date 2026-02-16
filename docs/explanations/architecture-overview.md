# Architecture overview

This explanation outlines the major components of the Cashu mint and how they interact.

## Hexagonal architecture

The codebase follows Clean Architecture and Hexagonal Architecture (Ports & Adapters). Business logic in the core protocol module has no knowledge of infrastructure concerns — it depends on abstractions (SPIs) that are implemented by external adapters.

```
                    ┌───────────────────────────────┐
                    │          REST API              │
                    │     (cashu-mint-rest)          │
                    └──────────┬────────────────────┘
                               │
                    ┌──────────▼────────────────────┐
                    │        NUT Layer               │
                    │  NUT01 · NUT02 · NUT03 · ...  │
                    │   (static protocol methods)    │
                    └──────────┬────────────────────┘
                               │
                    ┌──────────▼────────────────────┐
                    │        Task Layer              │
                    │  MintTokensTask · SwapTask     │
                    │  MeltTask · VerifyFeesTask     │
                    └──────────┬────────────────────┘
                               │
                    ┌──────────▼────────────────────┐
                    │       Service Layer            │
                    │  KeySetService · MintService   │
                    │  SignatureVaultService          │
                    └───────┬───────────┬───────────┘
                            │           │
               ┌────────────▼──┐   ┌────▼────────────┐
               │   Vault SPI   │   │  Gateway SPI     │
               │  (persistence)│   │  (payments)      │
               └──────┬────────┘   └────┬─────────────┘
                      │                 │
            ┌─────────▼──────┐  ┌───────▼──────────┐
            │ cashu-vault-jpa│  │ PhoenixdGateway   │
            │  (PostgreSQL)  │  │ DummyGateway      │
            └────────────────┘  └──────────────────┘
```

## Request flow

A typical mint-tokens request follows this path:

1. **REST Controller** receives `POST /v1/mint/bolt11` with blinded messages and a quote id.
2. **NUT04.mint()** validates the request against the Cashu specification.
3. **MintTokensTask** orchestrates the workflow: verify the quote is paid, check fees, sign blinded messages.
4. **SignBlindedMessageTask** calls the cryptographic signing service for each blinded message.
5. **Vault SPI** persists the resulting signatures so they can be restored later (NUT-09).
6. **Gateway SPI** confirms the Lightning payment status.
7. The controller returns the signed blind signatures to the caller.

## Module responsibilities

| Module | Role |
|--------|------|
| `cashu-mint-protocol` | Core business logic — NUT implementations, tasks, services, domain model |
| `cashu-mint-rest` | Spring Boot application, REST controllers, WebSocket (NUT-17), configuration |
| `cashu-mint-webhook` | Push-based payment notifications from gateways |
| `cashu-mint-rest-it` | Integration tests (Testcontainers, WireMock) |
| `cashu-mint-tools` | Test data generation (preload profiles) |
| `cashu-mint-observability` | Prometheus metrics, Grafana dashboards, health indicators |

## External dependencies

| Artifact | Purpose |
|----------|---------|
| `cashu-lib` | Shared Cashu data model and crypto utilities |
| `cashu-vault` / `cashu-vault-jpa` | Proof and signature persistence |
| `payment-adapter` | Gateway abstraction for Lightning payments |
| `cashu-voucher` | Gift-card / voucher system with Nostr publishing |

## Virtual threads

The project uses Java 21 Virtual Threads for efficient concurrency. All I/O-bound work (database queries, HTTP calls, WebSocket sends) runs on virtual threads. Spring Boot is configured with `spring.threads.virtual.enabled=true`, and custom executors use `Executors.newVirtualThreadPerTaskExecutor()`. See the [Virtual thread issues runbook](../runbooks/virtual-thread-issues.md) for troubleshooting.

## See also

- [Architecture and NUTs](architecture-and-nuts.md) — detailed module responsibilities and spec mapping
- [Module layers](../reference/module-layers.md) — package layout and wiring examples
- [Artifact dependencies](../reference/artifact-dependencies.md) — how modules, vaults, gateways, and libraries relate
