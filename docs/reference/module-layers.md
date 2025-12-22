# Module layers and package layout

This reference explains how the repository is organised and where to plug in new behaviour.

## Modules at a glance

The Maven build defines five modules (see `pom.xml`):

- `cashu-mint-protocol` – core protocol workflows (NUT-01/02/03/04/05/06/07/09), tasks, and vault/gateway integration services.
- `cashu-mint-rest` – Spring Boot service exposing the public mint API and wiring protocol services plus the preload-based mint loader for dev/test.
- `cashu-mint-observability` – Micrometer/Actuator auto-configuration for metrics, health indicators, and optional tracing.
- `cashu-mint-tools` – deterministic preload data generator and SQL renderer used to seed the vault with reproducible keysets.
- `cashu-mint-rest-it` – integration test harness for the REST module (voucher profile, H2, Spring context).

Administrative surfaces are published from the separate `cashu-mint-admin` repository; this project only references the published `cashu-mint-admin-rest` image in Docker Compose.

## `cashu-mint-protocol`

- **NUT entry points.** Static helpers under `xyz.tcheeric.cashu.mint.proto.nut` wrap the Cashu NUT specs. For example, `NUT04` handles mint quotes, voucher mint quotes, and minting, while `NUT05` handles melt quotes and melts (see `cashu-mint-protocol/src/main/java/xyz/tcheeric/cashu/mint/proto/nut`).
- **Task orchestration.** `xyz.tcheeric.cashu.mint.proto.tasks` contains small command objects (`MintTokensTask`, `SwapTask`, `MeltTask`, `RestoreSignaturesTask`, etc.) that compose protocol services with vault/gateway adapters.
- **Integration services.** Beans in `xyz.tcheeric.cashu.mint.proto.service` load mints from the vault, resolve gateway implementations, and expose utilities such as `SignatureVaultService` and `MintProtocolServiceFactory`.
- **Defaults and metadata.** `proto.properties` pins gateway defaults and voucher quote fees; `mint.yaml` carries NUT-06 metadata used by the REST app.

## `cashu-mint-rest`

- **Application bootstrap.** `CashuMintRestApplication` limits component scanning to `xyz.tcheeric.cashu.mint` packages and publishes startup diagnostics.
- **Controllers.** `CashuController` exposes the public Cashu API (`/v1` routes for keys, swap, mint, melt, info, checkstate, restore) and infers mint ids from proofs/keysets; `VoucherController` exposes voucher issuance/status when the `voucher` profile is active (see `cashu-mint-rest/src/main/java/xyz/tcheeric/cashu/mint/rest/controller`).
- **Services.** `PreloadMintLoadService` seeds the vault and serves keysets from the preload JSON in dev/test profiles, falling back to vault clients otherwise.
- **Clients.** Lightweight HTTP helpers in `xyz.tcheeric.cashu.mint.rest.client` (for example `CashuClient`) simplify invoking the REST API from other JVM callers.

## `cashu-mint-observability`

- **Auto-configuration.** `ObservabilityAutoConfiguration` wires Micrometer registries, task timing aspects, request interceptors, and health indicators when `cashu.observability.enabled=true`.
- **Metrics.** `MintMetrics`, `TaskMetrics`, `QuoteMetrics`, `GatewayMetrics`, and `VoucherMetrics` register counters/timers with the `cashu_mint_*` prefix; configuration is driven by `ObservabilityProperties`.
- **Tracing.** Optional OTLP export is configured via `TracingAutoConfiguration` when tracing is enabled.
- **Web wiring.** `MetricsHandlerInterceptor` instruments HTTP requests; health indicators check gateway/vault connectivity.

## `cashu-mint-tools`

- **Generators.** `MintPreloadDataGenerator` emits deterministic JSON for mint/keyset/key material; `MintPreloadSqlRenderer` renders SQL from that JSON.
- **Profiles.** Maven profiles `preload-json`, `preload-sql`, and `preload-all` run the generators (see `mint-preload.properties` for defaults).

## `cashu-mint-rest-it`

- **Purpose.** Provides Spring Boot integration tests for the REST module, including voucher profile coverage and H2-backed scenarios.
- **Execution.** Uses Surefire includes (`**/*IT.java`, `**/*IntegrationTest.java`) to run alongside unit tests inside the module.

## Wiring example

Mint endpoint wiring (`cashu-mint-rest` → protocol):

```java
@PostMapping("/v1/mint/{method}")
public ResponseEntity<PostMintResponse> mint(@RequestBody PostMintRequest<T> request,
                                             @PathVariable("method") String method) throws CashuErrorException {
    if (request.getQuoteId() == null || request.getBlindedMessages().isEmpty()) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }
    UUID mintId = inferMintIdFromMintOutputs(request);
    PaymentMethod paymentMethod = PaymentMethod.valueOf(method.toUpperCase());
    PostMintResponse response = NUT04.mint(
            mintId,
            request,
            paymentMethod,
            null,
            MintProtocolServiceFactory.getInstance(),
            mintLoadService,
            signatureVaultService
    );
    return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
}
```

The controller resolves the mint id from output keysets, derives the payment method from the path, and delegates to the NUT helper while reusing shared protocol services.
