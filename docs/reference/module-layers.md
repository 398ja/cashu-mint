# Module layers and package layout

This reference explains how the Cashu mint project is organised into modules and layers so that you can navigate the source tree and connect new integrations quickly.

## Modules at a glance

The Maven build defines three modules: the protocol core, the REST interface, and the administrative domain.【F:pom.xml†L12-L16】

## `cashu-mint-protocol`

The protocol module contains the reusable business logic and supporting utilities shared across user interfaces.

- **NUT entry points.** Classes under `xyz.tcheeric.cashu.mint.proto.nut` wrap Cashu NUT specifications in static helpers that orchestrate the lower layers. For example, `NUT04` exposes minting and quoting operations that delegate to tasks and services.【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/nut/NUT04.java†L1-L51】
- **Task orchestration.** The `xyz.tcheeric.cashu.mint.proto.tasks` package encapsulates multi-step workflows (loading mints, validating proofs, signing tokens) behind small command objects such as `MintTokensTask` that call into the protocol services.【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/tasks/MintTokensTask.java†L1-L40】
- **Integration services.** Beans in `xyz.tcheeric.cashu.mint.proto.service` provide the IO boundary: loading state from the vault, resolving gateway implementations, and adapting protocol objects to persistence models. The default implementation is Spring-aware so the REST module can inject optional collaborators like `MintInfoService`.【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/service/DefaultMintProtocolService.java†L1-L59】
- **Database access helpers.** The `DefaultMintLoadService` bridges to the vault database by delegating to `DBMintVault`, keeping storage-specific concerns outside the controllers.【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/service/DefaultMintLoadService.java†L1-L21】
- **Tooling.** Utilities under `xyz.tcheeric.cashu.mint.tools` generate deterministic preload data or render SQL scripts so that developers can seed databases with consistent fixtures.【F:cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/tools/MintPreloadSqlRenderer.java†L1-L34】
- **Reference metadata.** The `mint.yaml` resource ships a canonical description of a mint (name, contacts, supported NUTs) that both REST and CLI surfaces can reuse for diagnostics.【F:cashu-mint-protocol/src/main/resources/mint.yaml†L1-L34】

## `cashu-mint-rest`

The REST module turns the protocol into a Spring Boot service.

- **Application bootstrap.** `CashuMintRestApplication` limits component scanning to the `xyz.tcheeric.cashu` packages and publishes diagnostics at startup, keeping the runtime surface small when embedding in other Spring apps.【F:cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/CashuMintRestApplication.java†L1-L48】
- **HTTP controllers.** `CashuController` wires each HTTP route to the relevant NUT helper, injecting the shared protocol services (`MintLoadService`, `SignatureVaultService`) that encapsulate database and vault access.【F:cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/entity/controller/CashuController.java†L54-L165】
- **Reusable clients.** Lightweight wrappers in `xyz.tcheeric.cashu.mint.rest.client` (for example `CashuClient`) simplify calling the REST API from CLIs or other services by exposing typed accessors for keys and mint metadata.【F:cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/client/CashuClient.java†L12-L47】

## `cashu-mint-admin`

The administrative module holds the domain model for operator workflows so that a CLI or UI can reason about configuration state transitions without depending on Spring.

- The domain package models the mint lifecycle with aggregates, value objects, and invariants (see `MintAggregate` for the aggregate root that enforces revision and audit rules).【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/domain/MintAggregate.java†L1-L78】

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

The REST controller feeds request data into the static NUT API while relying on injected services to handle persistence and vault coordination.【F:cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/entity/controller/CashuController.java†L119-L125】

### CLI client hitting the REST API

```java
CashuClient client = new CashuClient();
MintInfo info = client.info();
List<KeySet> keysets = client.keys();
```

A CLI can reuse the `CashuClient` helper to reach the REST service without reimplementing HTTP calls, obtaining mint metadata and keysets through typed responses.【F:cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/client/CashuClient.java†L12-L38】
