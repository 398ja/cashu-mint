# Module layers and package layout

This reference explains how the Cashu mint project is organised into modules and layers so that you can navigate the source tree and connect new integrations quickly.

## Modules at a glance

The Maven build now defines five modules: the protocol core, the public REST interface,
the shared administrative domain, and dedicated adapters for the admin REST service and CLI
(see [`pom.xml`](../../pom.xml)).

- `cashu-mint-protocol` – reusable Cashu protocol workflows and tooling.
- `cashu-mint-rest` – Spring Boot service that exposes the public mint API.
- `cashu-mint-admin` – domain, ports, persistence adapters, and presenters for admin workflows.
- `cashu-mint-admin-rest` – Spring Boot service that adapts the admin domain to authenticated HTTP routes.
- `cashu-mint-admin-cli` – Picocli command line backed by the admin domain ports.

## `cashu-mint-protocol`

The protocol module contains the reusable business logic and supporting utilities shared across user interfaces.

- **NUT entry points.** Classes under `xyz.tcheeric.cashu.mint.proto.nut` wrap Cashu NUT specifications in static helpers that orchestrate the lower layers. For example, `NUT04` exposes minting and quoting operations that delegate to tasks and services (see [`NUT04.java`](../../cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/nut/NUT04.java)).
- **Task orchestration.** The `xyz.tcheeric.cashu.mint.proto.tasks` package encapsulates multi-step workflows (loading mints, validating proofs, signing tokens) behind small command objects such as `MintTokensTask` that call into the protocol services (see [`MintTokensTask.java`](../../cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/tasks/MintTokensTask.java)).
- **Integration services.** Beans in `xyz.tcheeric.cashu.mint.proto.service` provide the IO boundary: loading state from the vault, resolving gateway implementations, and adapting protocol objects to persistence models. The default implementation is Spring-aware so the REST module can inject optional collaborators like `MintInfoService` (implemented in [`DefaultMintProtocolService.java`](../../cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/service/DefaultMintProtocolService.java)).
- **Database access helpers.** The `DefaultMintLoadService` bridges to the vault database by delegating to `DBMintVault`, keeping storage-specific concerns outside the controllers (see [`DefaultMintLoadService.java`](../../cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/service/DefaultMintLoadService.java)).
- **Tooling.** Utilities under `xyz.tcheeric.cashu.mint.tools` generate deterministic preload data or render SQL scripts so that developers can seed databases with consistent fixtures (see [`MintPreloadSqlRenderer.java`](../../cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/tools/MintPreloadSqlRenderer.java)).
- **Reference metadata.** The `mint.yaml` resource ships a canonical description of a mint (name, contacts, supported NUTs) that both REST and CLI surfaces can reuse for diagnostics (stored at [`mint.yaml`](../../cashu-mint-protocol/src/main/resources/mint.yaml)).

## `cashu-mint-rest`

The REST module turns the protocol into a Spring Boot service.

- **Application bootstrap.** `CashuMintRestApplication` limits component scanning to the `xyz.tcheeric.cashu` packages and publishes diagnostics at startup, keeping the runtime surface small when embedding in other Spring apps (see [`CashuMintRestApplication.java`](../../cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/CashuMintRestApplication.java)).
- **HTTP controllers.** `CashuController` wires each HTTP route to the relevant NUT helper, injecting the shared protocol services (`MintLoadService`, `SignatureVaultService`) that encapsulate database and vault access (implemented in [`CashuController.java`](../../cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/entity/controller/CashuController.java)).
- **Reusable clients.** Lightweight wrappers in `xyz.tcheeric.cashu.mint.rest.client` (for example `CashuClient`) simplify calling the REST API from CLIs or other services by exposing typed accessors for keys and mint metadata (see [`CashuClient.java`](../../cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/client/CashuClient.java)).

## `cashu-mint-admin`

The administrative module houses the shared domain and application services that both adapter modules consume.

- **Domain model.** Aggregates, value objects, and invariants live under
  `xyz.tcheeric.cashu.mint.admin.domain`. The `MintAggregate` root enforces
  revision and audit rules (see [`MintAggregate.java`](../../cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/domain/MintAggregate.java)).
- **Use case interactors.** Application services under
  `xyz.tcheeric.cashu.mint.admin.application.service` orchestrate lifecycle,
  configuration, and operator workflows. For example,
  [`ManageMintLifecycleInteractor.java`](../../cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/application/service/ManageMintLifecycleInteractor.java)
  coordinates repository access and presenters for lifecycle changes.
- **Ports.** The `application.port` packages define incoming and outgoing interfaces
  so adapters stay decoupled from the domain (see
  [`ManageMintLifecycleUseCase.java`](../../cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/application/port/in/ManageMintLifecycleUseCase.java)
  and
  [`MintRepository.java`](../../cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/adapter/persistence/MintRepository.java)).
- **Persistence adapters.** JDBC-backed repositories in
  `adapter.persistence` implement the outgoing ports (for example
  [`RelationalMintRepository.java`](../../cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/adapter/persistence/RelationalMintRepository.java)).
- **Outbox dispatch.** Infrastructure for transactional outbox processing lives in
  `adapter.out.outbox`, including the
  [`LifecycleEventOutboxHandler`](../../cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/adapter/out/outbox/LifecycleEventOutboxHandler.java)
  and dispatcher.
- **Presenters.** Presenter implementations under `presentation` render lifecycle
  summaries for both CLI and REST consumers (see
  [`LifecycleSummaryPresenter.java`](../../cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/presentation/lifecycle/LifecycleSummaryPresenter.java)).

## `cashu-mint-admin-rest`

This Spring Boot module adapts the admin domain to authenticated HTTP endpoints.

- **Application entry point.**
  [`CashuMintAdminRestApplication`](../../cashu-mint-admin-rest/src/main/java/xyz/tcheeric/cashu/mint/admin/rest/CashuMintAdminRestApplication.java)
  scans the admin packages and configures security filters for the `/admin` surface.
- **Controllers.** Classes under `controller` expose lifecycle, configuration,
  user, and alert routes (for example
  [`LifecycleAdminController.java`](../../cashu-mint-admin-rest/src/main/java/xyz/tcheeric/cashu/mint/admin/rest/controller/LifecycleAdminController.java)).
- **DTOs and presenters.** The `dto` package defines request/response payloads,
  while presenters such as
  [`LifecycleSummaryApiPresenter`](../../cashu-mint-admin-rest/src/main/java/xyz/tcheeric/cashu/mint/admin/rest/presenter/LifecycleSummaryApiPresenter.java)
  transform admin-domain summaries into API responses.
- **Services.** Coordinator classes in `service` translate HTTP input into admin
  use-case invocations, handling authentication and error reporting (see
  [`AdminLifecycleService`](../../cashu-mint-admin-rest/src/main/java/xyz/tcheeric/cashu/mint/admin/rest/service/AdminLifecycleService.java)).

## `cashu-mint-admin-cli`

The CLI module packages a Picocli launcher that calls the admin domain through ports.

- **Launcher.**
  [`MintAdminCliApplication`](../../cashu-mint-admin-cli/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/MintAdminCliApplication.java)
  wires port implementations and builds the command tree.
- **Commands.** Subcommands live under `command` and map CLI arguments to port
  calls (see [`MintCommand.java`](../../cashu-mint-admin-cli/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/command/MintCommand.java)).
- **Ports.** The `port` package declares the interfaces adapters must satisfy.
  Stub implementations in `port.stub` make it easy to rehearse commands offline
  (for example
  [`StubMintStatusPort`](../../cashu-mint-admin-cli/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/port/stub/StubMintStatusPort.java)).
- **Presentation.** CLI presenters in `presentation` reuse the shared lifecycle
  view models while adapting output for tables or JSON (see
  [`LifecycleSummaryCliPresenter`](../../cashu-mint-admin-cli/src/main/java/xyz/tcheeric/cashu/mint/admin/cli/presentation/lifecycle/LifecycleSummaryCliPresenter.java)).

## Wiring examples

### REST endpoint to protocol

```java
@PostMapping("/mint/{mintId}/{method}")
public ResponseEntity<PostMintResponse> mint(@RequestBody PostMintRequest<T> request,
                                             @PathVariable("method") String method,
                                             @PathVariable("mintId") String mintId) throws CashuErrorException {
    PostMintResponse response = NUT04.mint(
            UUID.fromString(mintId), request, PaymentMethod.valueOf(method.toUpperCase()), signatureVaultService);
    return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
}
```

The REST controller feeds request data into the static NUT API while relying on injected services to handle persistence and vault coordination (see [`CashuController.java`](../../cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/entity/controller/CashuController.java)).

### CLI client hitting the REST API

```java
CashuClient client = new CashuClient();
MintInfo info = client.info();
List<KeySet> keysets = client.keys();
```

A CLI can reuse the `CashuClient` helper to reach the REST service without reimplementing HTTP calls, obtaining mint metadata and keysets through typed responses (see [`CashuClient.java`](../../cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/client/CashuClient.java)).
