# Cashu Mint Admin Technical Analysis

_Explanation_: This document provides an explanation-style technical analysis of the Cashu Mint Admin module, detailing its
architecture, abstractions, and interaction patterns through the lens of Robert C. Martin's Clean Architecture principles. It
is intended to guide contributors who need to understand or evolve the admin capabilities without regressing the CLI-first
commitments captured in the project specification.

## 1. Scope and Context

The admin module supplies operational tooling for managing Cashu mint instances. In the current monorepo it collaborates with:

- **cashu-mint-protocol** for domain concepts shared across services (mints, proofs, keys, reserves).
- **cashu-mint-rest** for HTTP/JSON exposure of administrative workflows and health signals.
- **External dependencies** including persistent storage (PostgreSQL), eventing surfaces (webhooks, email gateways), and
  authentication providers (OAuth2/JWT).

Phase 1 of delivery is intentionally CLI-centric: every administrative workflow must be invokable, auditable, and observable
from the terminal. A future web console will reuse the same use-case interactor layer, ensuring separation between policies and
their presentation.

### 1.1 Bounded Context Definition

Within the broader Cashu system the admin module owns lifecycle, configuration, observability, and access control concerns for
mint instances. It integrates with, but does not embed, the minting engine that issues or redeems tokens. Its responsibilities
are:

1. Governing mint creation, update, suspension, resumption, and retirement workflows.
2. Maintaining configuration sets (fees, rate limits, payout policies) with change tracking and rollback support.
3. Emitting operational telemetry and actionable alerts when thresholds are crossed or incidents are declared.
4. Administering users, roles, and approvals across CLI and API clients.

## 2. Architectural Forces

| Force | Description | Architectural Responses |
| --- | --- | --- |
| **CLI-first operations** | Operators must be productive entirely from terminal environments. | Treat CLI as a primary Framework & Driver; provide adapters for scriptable JSON/YAML payloads and progress polling; maintain idempotent use cases for automation. |
| **Security & audit** | Every action must be attributable and reversible. | Centralize identity at Interface Adapter boundary, include audit trails in entities, provide immutable event sourcing, enforce RBAC policies inside use cases. |
| **Extensibility** | New mint types, payment backends, or notification channels should not require rewrites. | Use ports/adapters for external integrations, keep entity boundaries stable, allow driver-specific modules to be added without altering core. |
| **Reliability under change** | Lifecycle errors can threaten issued e-cash guarantees. | Encapsulate invariants in entities, run side effects through resilient adapters with retry/backoff, and adopt transactional outbox patterns for notifications. |
| **Consistency across interfaces** | CLI and future web clients must exhibit identical behaviour. | Encapsulate policies in use-case interactors, expose them via ports consumed by CLI commands and HTTP controllers, and share DTO assemblers. |

These forces motivate a concentric architecture where business policies do not depend on UI, database, or vendor-specific code.

## 3. Clean Architecture Layers

```
┌──────────────────────────────┐
│        Frameworks & Drivers  │  (Spring Boot REST, Picocli CLI, Scheduler, Messaging, Persistence)
├──────────────────────────────┤
│        Interface Adapters    │  (CLI command handlers, REST controllers, DTO mappers, gateways)        ← depends inward
├──────────────────────────────┤
│   Application Business Rules │  (Use case interactors, input/output ports, policies)
├──────────────────────────────┤
│        Enterprise Rules      │  (Entities, aggregates, value objects, domain events)
└──────────────────────────────┘
```

Dependencies point inward; only data structures cross boundaries outward.

### 3.1 Enterprise Rules (Entities Layer)

Entities encapsulate the invariants of mint administration. Core aggregates and value objects include:

- **MintAggregate**: composed of `MintId`, lifecycle state, environment bindings, configuration version references, and
  guardrails (rate limits, reserve thresholds). Enforces state machine transitions and audit stamping.
- **ConfigurationSet**: versioned snapshot of operational policies (fee schedules, payout channels, key rotation cadence). Holds
  invariants for diff/rollback and ensures compatibility with active mint state.
- **OperatorAccount**: represents admins, operators, auditors. Encapsulates RBAC roles, delegated permissions, and credential
  metadata; coordinates with identity providers via domain services.
- **AuditTrail & DomainEvent**: immutable record of administrative actions (who, what, when, payload hash). Provides hooks for
  event publishing without leaking infrastructure concerns.
- **NotificationPolicy**: expresses alert thresholds and delivery channels (email, webhook, queue). Maintains acknowledgement
  requirements and escalation windows.

These entities should be implemented as POJOs in `cashu-mint-admin` or extracted to `cashu-mint-protocol` when shared. They must
not depend on Spring, JPA, or CLI toolkits.

### 3.2 Application Business Rules (Use Case Layer)

Use-case interactors orchestrate entities and enforce policies. Each interactor exposes input/output ports to decouple from
adapters:

| Use Case | Purpose | Input Port Responsibilities | Output Port Contracts |
| --- | --- | --- | --- |
| **ManageMintLifecycle** | Create, update, pause/resume, retire mint instances with audit logging. | Validate commands, consult `MintAggregate` for state transitions, persist transactional changes. | Emit summary DTOs, produce lifecycle events, schedule background orchestration. |
| **ManageConfiguration** | Version, diff, and rollback configuration sets. | Accept JSON/YAML payloads, validate against schema, link to target mints. | Return diff results, generate change approval tasks, trigger notifier port when approvals succeed. |
| **MonitorMintHealth** | Provide status dashboards and alerts. | Aggregate telemetry from observers, evaluate thresholds, query read models. | Stream status snapshots to CLI/REST, publish alert events, update acknowledgement tasks. |
| **ExecuteOperationalControls** | Run maintenance, key rotations, or recovery workflows. | Sequence long-running jobs, enforce guardrails (quorum approval), coordinate with scheduler. | Update progress states, raise incidents, persist runbooks, return CLI-progress tokens. |
| **AdministerAccess** | Provision users, assign roles, manage tokens. | Validate role changes, enforce separation of duties, integrate with identity provider port. | Return approval receipts, update audit logs, push notifications to review queues. |
| **ManageNotifications** | Configure alert policies and integration endpoints. | Validate channel metadata, store secrets via secure vault port. | Return channel status, test deliverability, enqueue initial heartbeat messages. |

Each interactor is pure Java, orchestrating repositories, event publishers, and other gateways through injected interfaces.
Transaction boundaries and retries are centralised here, not in controllers.

### 3.3 Interface Adapters Layer

Adapters translate between external protocols and use-case boundaries:

- **CLI Adapter**: Terminal commands implemented with a toolkit such as Picocli or Spring Shell. Each command maps to a use-case
  input port. JSON/YAML payloads are deserialized into request models, while table/JSON outputs are produced via presenter
  interfaces. Progress polling uses asynchronous job IDs returned by use cases.
- **REST Adapter**: Spring MVC/WebFlux controllers (defined in `cashu-mint-rest`) implement HTTP endpoints for automation and
  third-party integration. They convert HTTP requests into input port DTOs and rely on presenters to shape responses.
- **Persistence Adapter**: Repository implementations using Spring Data JDBC/JPA or jOOQ encapsulate data access. They map domain
  aggregates to relational schemas (PostgreSQL) and support transactional write models plus read-optimized projections.
- **Notification Adapter**: Integrations to SMTP, webhook POST, message queues, or incident platforms. Implement the output ports
  triggered by `ManageNotifications` and `MonitorMintHealth` use cases.
- **Identity Adapter**: Bridges to OAuth2/JWT providers, enforcing RBAC decisions before invoking use cases.
- **Observation Adapter**: Surfaces metrics and logs via Spring Boot Actuator endpoints and CLI-friendly reports.

Adapters may depend on frameworks but must only communicate with use cases via ports to preserve directional boundaries.

### 3.4 Frameworks & Drivers

Framework concerns stay at the perimeter:

- **Spring Boot** orchestrates REST controllers, dependency injection, scheduling, and Actuator endpoints.
- **CLI Runtime** packages commands into a standalone executable (native image or shaded JAR). Build tooling (e.g., GraalVM,
  Jib) belongs here.
- **Database Drivers** (PostgreSQL, H2 for tests) provide persistence connectivity.
- **Messaging / Email libraries** implement notification delivery.
- **Observability stack** (OpenTelemetry exporters, logging appenders) integrates at this layer.

Framework selection should remain replaceable; tests must target use cases and entities to avoid brittle coupling.

## 4. Data Flow Scenarios

### 4.1 Mint Creation (CLI)

1. Operator executes `mint create --file config.yaml`.
2. CLI Adapter parses YAML into a `CreateMintCommand` and invokes `ManageMintLifecycleInputPort`.
3. Use case validates configuration, instantiates `MintAggregate`, and persists via repository port.
4. On success, the interactor emits `MintLifecycleEvent` through the output port.
5. Presenter formats the success response (JSON summary + audit reference) returned to CLI.
6. Notification adapter receives lifecycle event for downstream observers (e.g., Slack webhook).

### 4.2 Emergency Pause via REST

1. Incident automation calls `POST /admin/mints/{id}/pause` with JWT credentials.
2. REST controller authenticates request, maps it to `PauseMintRequest` and delegates to `ManageOperationalControls` input port.
3. Use case enforces RBAC approvals, updates `MintAggregate` state, and triggers scheduler adapter for safe shutdown tasks.
4. Presenter returns HTTP 202 with progress token; CLI and web clients can poll job status.
5. Audit trail entry is persisted; notifications raised if pause breaches SLAs.

### 4.3 Configuration Rollback

1. Auditor runs `mint config rollback --mint M123 --version 42`.
2. CLI adapter issues rollback request to `ManageConfiguration` use case.
3. Use case computes diffs, verifies invariants, persists new version, and emits configuration change event.
4. Output port notifies observability adapter to mark the change; CLI receives human-readable summary and diff artifact location.

## 5. Persistence and Data Modeling

- **Write Model**: Normalized tables for mints, configuration versions, user accounts, audit events, notification policies. Each
  table stores metadata required by entities (e.g., state machine version, approval quorum, correlation IDs).
- **Read Model**: Denormalized projections for dashboards and CLI summaries. Built via event listeners or database views to keep
  read paths responsive.
- **Transactions**: Use cases demarcate transactions. Repositories implement unit-of-work semantics; asynchronous follow-up work
  (alerts, webhooks) relies on transactional outbox or message queue ports to avoid inconsistent state.
- **Secrets Handling**: Sensitive credentials (SMTP API keys, OAuth secrets) are stored via a vault gateway rather than the
  primary database. Use cases only hold opaque handles.

## 6. Cross-cutting Concerns

- **Security**: Authentication/authorization enforced at adapter boundary; use cases receive principal context for fine-grained
  checks. Commands that modify state require multi-factor approval workflows encoded in entities.
- **Auditing**: Every use case emits domain events persisted alongside business data. Audit viewers are read-only adapters that
  query projections, ensuring tamper evidence.
- **Observability**: Structured logging with correlation IDs flows from CLI/REST into use cases. Metrics (latency, success rate,
  queue length) exported via Actuator and consumed by CLI status commands.
- **Validation**: Input validation occurs both at adapter level (schema, format) and entity level (business invariants). Failed
  validations surface through presenters with machine-readable error codes for automation.
- **Error Handling**: Use cases expose rich error models (retryable vs terminal). CLI adapters convert them into exit codes;
  REST adapters translate into HTTP status and problem+json payloads.

## 7. Testing Strategy

Aligned with Clean Architecture, the testing pyramid emphasizes inner layers:

1. **Entity tests**: Verify state transitions, approval logic, and invariants with no framework dependencies.
2. **Use-case tests**: Exercise interactors with mocked ports, covering success and failure paths (e.g., paused mint cannot be
   resumed without quorum approval).
3. **Adapter tests**: Validate CLI command parsing, REST endpoint serialization, and repository mappings. These may use Spring
   Boot slices or Picocli test harnesses.
4. **Integration tests**: Spin up full application contexts with Postgres/H2, verifying end-to-end flows (CLI script invoking REST
   API, observing audit logs and notifications).
5. **Contract tests**: Ensure CLI and REST adapters adhere to shared DTO schemas consumed by external automation.

Testing artefacts belong near the layers they exercise; for example, CLI command tests live with adapter code, while use-case
unit tests remain pure JUnit.

## 8. Collaboration with Other Modules

- **cashu-mint-protocol**: Supplies reusable entities (proof formats, mint keys). Admin entities may depend on these types but
  must keep protocol unaware of admin policies to avoid circular dependencies.
- **cashu-mint-rest**: Hosts REST controllers and Actuator endpoints. It should only depend on admin use-case interfaces, never
  on infrastructure-specific implementations, to avoid leaking framework details inward.
- **External Services**: Payment gateways, notification providers, and secret stores are represented as ports in the use-case
  layer. Their concrete adapters live in dedicated packages or modules to remain replaceable.

## 9. Roadmap Considerations

- **Phase 1 completeness** requires CLI coverage of all lifecycle, configuration, monitoring, operational, and identity flows.
  Before introducing web UI, ensure CLI telemetry indicates stable adoption and no critical gaps.
- **Web console (Phase 2)** should be treated as another Interface Adapter. It consumes the same use cases and presenters,
  allowing UI development without destabilizing business rules.
- **Ecosystem alignment**: Continue aligning with NUT specifications for mint behaviour. When new NUTs introduce capabilities,
  update entities and use cases first, then adapt CLI/REST surfaces.
- **Documentation & Tooling**: Maintain parity between CLI help, REST OpenAPI specifications, and this technical analysis. Update
  diagrams and interface contracts as new adapters or use cases are added.

By maintaining the boundaries advocated in Clean Architecture, the admin module can evolve interfaces and frameworks while
preserving the core business policies that keep Cashu mints secure, auditable, and operator-friendly.
