# Architecture

The admin module follows Clean Architecture (Robert C. Martin) with four layers. Each layer depends only on the layers inside it.

## Layer overview

```
┌─────────────────────────────────────────────┐
│              Frameworks & Drivers            │
│  mint-admin-rest · mint-admin-cli · web UI  │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│            Interface Adapters                │
│  Controllers · Presenters · DTOs · Ports    │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│             Use Cases                        │
│  Lifecycle · Config · Users · Alerts · Ops  │
└──────────────────┬──────────────────────────┘
                   │
┌──────────────────▼──────────────────────────┐
│              Entities                        │
│  Mint · ConfigRevision · User · Alert       │
│  HealthSnapshot · OperationalControl        │
└─────────────────────────────────────────────┘
```

## Module responsibilities

| Module | Layer | Role |
|--------|-------|------|
| `mint-admin-core` | Entities + Use Cases | Domain model, use case ports, Flyway migrations, services |
| `mint-admin-cli` | Framework | Picocli CLI commands, stub adapters, I/O formatting |
| `mint-admin-rest` | Framework | Spring Boot REST controllers, DTO mapping, security filter |
| `mint-admin-web` | Framework | React SPA, Radix UI components, TanStack Query state |
| `mint-admin-tests` | — | Integration and E2E test harnesses |

## CLI-first design

The admin was designed CLI-first: every operation is available from the command line before being exposed via REST or web. The CLI ships with stub adapters so operators can explore commands offline, then switches to HTTP mode by passing `--api-url`.

## Data flow

A typical lifecycle request flows through:

1. **CLI/REST/Web** receives user input
2. **Port interface** defines the contract (e.g., `LifecyclePort`)
3. **Use case** validates business rules and orchestrates the workflow
4. **Repository** persists state changes via Flyway-managed tables
5. **Outbox** publishes domain events for audit and notification subscribers
6. **Presenter** formats the response for the calling interface

## Database schema

Ten Flyway migrations (`V1`–`V10`) establish the schema. See [Configure persistence](../how-to/configure-mint-admin-persistence.md) for the full migration table.

Key entity groups:
- **Mint lifecycle**: `mints`, `mint_lifecycle_history`, `mint_aggregate_snapshots`, `mint_lifecycle_approval_states`
- **Configuration**: `configuration_revisions`
- **Users**: `admin_users`, `operator_accounts`
- **Alerts**: `admin_alerts`, `admin_alert_escalations`
- **Operations**: `operational_controls`, `mint_health_snapshots`
- **Audit**: `audit_events`, `admin_outbox`

## Planning documents

Detailed design documents live in the `project/` directory:

- `project/specification.md` — functional and non-functional requirements
- `project/technical-analysis.md` — Clean Architecture deep-dive with port contracts
- `project/M1.md` through `project/M8.md` — milestone breakdowns

## See also

- [Architecture overview (main mint)](../../docs/explanations/architecture-overview.md) — how the admin fits with the mint
- [Configure persistence](../how-to/configure-mint-admin-persistence.md) — database setup
