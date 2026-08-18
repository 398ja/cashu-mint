# Domain code never touches MeterRegistry directly

> **Status: accepted, rolling out.** This records the decision, not the current
> state of the tree. The seam and the melt area's recorder landed in #340;
> voucher (#341) and mint/issuance + webhook (#342) still emit through the
> `MintIntegrityContext.meterRegistry()` accessor, which is deleted in #343.
> The guard test and the generated `metrics-reference.md` described under
> Consequences arrive in #347 and #348.

Domain code emits metrics through typed recorder interfaces declared in `cashu-mint-protocol` (one per domain area) and implemented in `cashu-mint-observability`. The static `MintIntegrityContext.meterRegistry()` accessor is removed, so no call site can invent a metric name inline.

We did this because the previous arrangement — a raw `MeterRegistry` handed to domain code through a service locator — produced two disconnected observability systems. Four metric classes (`MintMetrics`, `QuoteMetrics`, `VoucherMetrics`, `GatewayMetrics`, ~50 metrics) were registered as beans and never called, while sixteen ad-hoc counters were declared as string literals scattered across five modules. Dashboards and alert rules charted the dead set; the live set appeared in no doc and on no dashboard. Nothing structurally connected a declared metric to a consumer, so both halves drifted unnoticed. `TaskMetrics` and `LockMetrics`, the only two behind a recorder seam, are also the only two that stayed alive.

## Considered Options

**Spring `ApplicationEvent`s** (the spec-036 `TraceMintProducer` / `Nut17EventPublisher` pattern) were rejected: they would put `spring-context` into `cashu-mint-protocol`, which today carries Spring only as a test dependency, and they buy a decoupling this problem does not need.

**Keeping the raw registry behind a naming guard test** was rejected because it polices names without giving them a home — the sixteen scattered counters would remain scattered.

## Consequences

A guard test enforces both directions: every metric declared on a recorder must have at least one production call site, and every `cashu_mint_*` name referenced by a dashboard or alert rule must resolve to a declared recorder metric. `metrics-reference.md` is generated from the recorders rather than hand-maintained.
