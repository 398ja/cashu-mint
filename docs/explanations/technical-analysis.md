# Technical analysis of the Cashu mint implementation

This explanation documents the current technical shape of the Cashu mint. It inventories the modules, runtime dependencies, and
critical code paths that implement the Cashu protocol so the admin roadmap can plan against real implementation details.

## Scope and context

The project is a multi-module Maven build that ships a protocol library, a Spring Boot REST service, and an administrative
module scaffolded for future CLI tooling.【F:pom.xml†L12-L21】 The protocol module depends on the shared Cashu libraries, gateway
implementations, and vault components maintained in sibling repositories, aligning all modules on Java 21 and Spring Boot 3.5.5
through the parent POM.【F:cashu-mint-protocol/pom.xml†L18-L63】【F:pom.xml†L18-L55】 Runtime metadata such as mint identity,
contact points, and NUT coverage is loaded from `mint.yaml`, while `proto.properties` and `rest.properties` define the default
gateway bindings that drive payment interactions.【F:cashu-mint-protocol/src/main/resources/mint.yaml†L1-L38】【F:cashu-mint-protocol/src/main/resources/proto.properties†L1-L14】【F:cashu-mint-rest/src/main/resources/rest.properties†L1-L7】

## Core protocol services

### NUT coverage and orchestration

The `cashu-mint-protocol` module exposes static entry points for the implemented NUT specifications. `NUT02` and `NUT03`
provide key management and swap flows; `NUT04` handles minting by issuing quotes, checking status, and producing signed tokens;
`NUT05` mirrors that for melting; `NUT06` surfaces mint metadata; `NUT07` reports proof states; and `NUT09` restores previously
stored signatures.【F:cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/entity/controller/CashuController.java†L16-L112】 Each static facade delegates to task classes such as
`MintQuoteTask`, `MintTokensTask`, `MeltQuoteTask`, `MeltTask`, `CheckStateTask`, and `RestoreSignaturesTask`, ensuring a single
execution path is shared by direct library use and the REST service.【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/nut/NUT04.java†L12-L51】【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/tasks/MintQuoteTask.java†L1-L46】【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/tasks/MintTokensTask.java†L1-L61】【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/tasks/MeltTask.java†L1-L62】

### Concurrency, signing, and vault coordination

Minting and melting are serialized through a shared `ReentrantLock` to prevent double-spend windows when proofs are consumed or
signatures generated.【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/util/ThreadUtil.java†L1-L7】【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/tasks/MintTask.java†L46-L74】【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/tasks/MeltTask.java†L52-L104】 During minting the task verifies payment status through the configured
gateway, signs each blinded message, and persists the signatures via a `SignatureVaultService` abstraction that currently stores
them in-memory by default.【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/tasks/MintTask.java†L48-L68】【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/service/SignatureVaultService.java†L1-L9】【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/service/DefaultSignatureVaultService.java†L1-L27】 Melting re-verifies proofs by deriving private keys from the mint state,
checks fee reserves, invalidates proofs, and records the Phoenixd preimage after payment completes.【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/tasks/MeltTask.java†L63-L117】

### Mint, proof, and keyset persistence

The protocol code maps protocol domain objects into vault persistence models so the REST layer and future CLI can reuse the same
storage primitives. `DefaultMintLoadService` and `DefaultMintVaultService` wrap the shared `DBMintVault`, while
`DefaultProofVaultService` fronts `DBProofVault` for proof storage, invalidation, and archival.【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/service/DefaultMintLoadService.java†L1-L21】【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/service/DefaultMintVaultService.java†L1-L18】【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/service/DefaultProofVaultService.java†L1-L30】 `MintProtocolUtil` converts between the protocol
model and vault entities, resolves gateway classes, and retrieves key material for proof verification using the stored unit and
amount metadata.【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/util/MintProtocolUtil.java†L1-L119】

### Gateway integration and configuration

`DefaultMintProtocolService` centralizes gateway creation, optionally consulting mint metadata to pick the right unit before
loading the implementation class via the `MintProtocolServiceFactory` singleton and gateway loader.【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/service/DefaultMintProtocolService.java†L1-L65】【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/service/MintProtocolServiceFactory.java†L1-L16】【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/util/MintProtocolUtil.java†L121-L195】 Gateway mappings originate from `proto.properties` and are overridden by REST-level defaults in
`rest.properties`, ensuring CLI or REST clients resolve to Phoenixd unless configuration overrides them.【F:cashu-mint-protocol/src/main/resources/proto.properties†L1-L14】【F:cashu-mint-rest/src/main/resources/rest.properties†L1-L7】

## REST delivery layer

The REST module is a Spring Boot 3.5 application exposing the Cashu API. `CashuController` wires the protocol tasks into HTTP
endpoints that serve keyset discovery, minting, melting, swaps, state checks, info retrieval, and signature restoration.
Responses are serialized with the shared entity classes, while payment methods are resolved from path variables.【F:cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/entity/controller/CashuController.java†L16-L141】 Structured errors thrown by protocol code are
captured by `handleCashuError`, which inspects the exception payload, distinguishes `404` responses, and falls back to a generic
error document when parsing fails.【F:cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/entity/controller/CashuController.java†L143-L199】 A lightweight Java client built on `RestTemplate` exercises the published endpoints and derives the base URL from
system properties, easing integration testing and scripting.【F:cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/client/CashuClient.java†L1-L44】【F:cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/client/BaseClient.java†L1-L19】

Application configuration is externalized: server bindings and datasource properties prefer environment variables, while the
Actuator health probes are enabled for container readiness. Logging defaults target DEBUG for project code to support
troubleshooting.【F:cashu-mint-rest/src/main/resources/application.properties†L1-L24】 Gateway mappings for BOLT11 requests are duplicated here so operators can override them without touching the protocol
classpath.【F:cashu-mint-rest/src/main/resources/rest.properties†L1-L7】

## Infrastructure and deployment

`docker-compose.yml` provisions PostgreSQL instances for the mint, vault, and gateway, the Phoenixd mock, gateway services, and
the mint REST container wired together on a shared network with health checks and overridable ports.【F:docker-compose.yml†L1-L153】【F:docker-compose.yml†L154-L238】 The REST module build config packages the Spring Boot
service and publishes container images via Jib, while runtime dependencies include the protocol jar, Postgres driver, Actuator,
and the shared Cashu libraries.【F:cashu-mint-rest/pom.xml†L10-L81】 This layout allows local development stacks to match production topologies and provides clear injection points for future CLI
services.

## Security, resilience, and observability notes

Current endpoints expose no authentication or RBAC guards; hardening will require integrating the planned admin module to enforce
roles before exposing the REST service directly.【F:cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/entity/controller/CashuController.java†L16-L141】 Health probes are available via Spring Boot Actuator, but there is no dedicated metrics pipeline beyond standard
Spring instrumentation yet.【F:cashu-mint-rest/src/main/resources/application.properties†L9-L15】 Error handling depends on reflective access to recover structured payloads, which may be brittle under security managers.【F:cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/entity/controller/CashuController.java†L143-L199】 The default signature vault keeps
state in a `ConcurrentHashMap`, so operator-visible durability depends on swapping in a persistent implementation before relying
on restore flows across process restarts.【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/service/DefaultSignatureVaultService.java†L1-L27】 Finally, the global mint/melt lock preserves correctness but serializes all requests; scaling will need a more granular locking
strategy as concurrency requirements grow.【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/util/ThreadUtil.java†L1-L7】【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/tasks/MintTask.java†L46-L74】【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/tasks/MeltTask.java†L52-L104】

## Risks and recommendations

- **Persist signature history.** Replace the in-memory `DefaultSignatureVaultService` with a vault-backed implementation so
  restore operations survive restarts and support horizontal scaling.【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/service/DefaultSignatureVaultService.java†L11-L27】
- **Harden error handling.** Remove reflective access in `handleCashuError` or sandbox it behind feature flags to avoid
  compatibility issues under stricter JVM policies.【F:cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/entity/controller/CashuController.java†L143-L199】
- **Introduce scoped locks.** Replace the single global `MINT_MELT_LOCK` with per-mint or per-keyset synchronization to unlock
  throughput while preserving proof safety.【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/util/ThreadUtil.java†L1-L7】【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/tasks/MintTask.java†L46-L74】
- **Add authentication and RBAC.** Integrate the planned admin module security stack so REST endpoints enforce operator
  permissions before milestone M5 goes live.【F:cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/entity/controller/CashuController.java†L16-L141】
- **Define telemetry adapters.** Extend the Actuator wiring with metrics exports (Prometheus/OpenTelemetry) to satisfy the CLI
  health-reporting goals in M3.【F:cashu-mint-rest/src/main/resources/application.properties†L9-L15】

## Implications for milestones

- **M1 – Mint Lifecycle Management:** Lifecycle commands must orchestrate minting, melting, and proof archival across the
  serialized task pipeline and vault services described above, ensuring the CLI mirrors the REST execution model.【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/tasks/MintTokensTask.java†L33-L61】【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/tasks/MeltTask.java†L63-L117】
- **M2 – Settings and Configuration:** Gateway resolution and metadata loading rely on property files and `MintInfo`, so the CLI
  must expose the same configuration surfaces and validation outlined in the integration layer.【F:cashu-mint-protocol/src/main/resources/proto.properties†L1-L14】【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/service/DefaultMintProtocolService.java†L1-L65】
- **M3 – Mint Status and Health:** The existing Actuator endpoints provide a base health signal, but richer telemetry adapters
  are needed to meet the milestone’s observability objectives.【F:cashu-mint-rest/src/main/resources/application.properties†L9-L15】
- **M4 – Operational Controls:** The global mint/melt lock highlights where orchestration must evolve to support concurrent CLI
  operations without sacrificing proof safety.【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/util/ThreadUtil.java†L1-L7】
- **M5 – User and Permission Management:** REST endpoints lack authentication, underscoring the need for the CLI-driven RBAC
  layer before exposing administrative workflows broadly.【F:cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/entity/controller/CashuController.java†L16-L141】
- **M6 – Notifications and Alerts:** Gateway payment status checks and melt workflows provide hooks for alert generation once the
  CLI surfaces the Phoenixd events and proof mutations.【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/tasks/MeltTask.java†L63-L117】
- **M7 – API Interface:** The Spring Boot controller already encapsulates the REST surface area that the CLI and SDKs will
  consume, framing the schema work described in this milestone.【F:cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/entity/controller/CashuController.java†L16-L141】
- **M8 – Non-Functional Readiness:** Identified risks around durability, locking, and observability map directly to the security
  and reliability acceptance criteria for the CLI-first release.【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/service/DefaultSignatureVaultService.java†L1-L27】【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/util/ThreadUtil.java†L1-L7】【F:cashu-mint-rest/src/main/resources/application.properties†L9-L15】
