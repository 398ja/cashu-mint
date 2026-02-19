# Transactional Outbox Pattern

The admin module uses the **transactional outbox pattern** to guarantee that domain state changes and their corresponding events are never out of sync. When a mint lifecycle action occurs (e.g. creation, activation), the business state change and an outbox message are written in the same database transaction. A background scheduler then polls the outbox table, dispatches each message to one or more handlers, and marks it as delivered.

This eliminates the dual-write problem: there is no window where the aggregate is saved but the event is lost, or vice versa.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│  REST / CLI request                                     │
│       │                                                 │
│       ▼                                                 │
│  ManageMintLifecycleInteractor                          │
│       │                                                 │
│       ├── save(MintAggregate)         ─┐                │
│       │                                │  single DB     │
│       └── eventPublisher.publish(...)  │  transaction   │
│               │                        │                │
│               ▼                       ─┘                │
│    TransactionalOutboxPublisher                         │
│               │                                         │
│               ▼                                         │
│         admin_outbox table                              │
└─────────────────────────────────────────────────────────┘
                │
     ┌──────────┘  @Scheduled(fixedDelay = 5000)
     ▼
┌──────────────────────────────────────────────┐
│  LifecycleEventOutboxDispatcher              │
│       │                                      │
│       ▼  (per message, on a virtual thread)  │
│  CompositeOutboxMessageHandler               │
│       │                                      │
│       ├── LifecycleEventOutboxHandler        │
│       │     └─ projects read-model views     │
│       │     └─ appends lifecycle history     │
│       │                                      │
│       └── VaultProvisioningOutboxHandler     │
│             └─ provisions vault (CREATED)    │
│             └─ archives vault  (RETIRED)     │
└──────────────────────────────────────────────┘
```

## Outbox Table

The `admin_outbox` table stores pending messages. Its schema (from `V1__create_admin_schema.sql`):

| Column | Type | Purpose |
|--------|------|---------|
| `event_id` | `UUID PK` | Unique message identifier |
| `aggregate_id` | `UUID` | The mint ID this event belongs to |
| `aggregate_type` | `VARCHAR` | Always `"MintAggregate"` |
| `event_type` | `VARCHAR` | Lifecycle event name (e.g. `CREATED`, `PAUSED`, `RETIRED`) |
| `payload` | `TEXT` | Full JSON body of the domain event |
| `attributes` | `TEXT` | JSON headers: `{"schema":"admin.mint-lifecycle.v1", "versionTag":"v1"}` |
| `occurred_at` | `TIMESTAMPTZ` | When the domain event occurred |
| `available_at` | `TIMESTAMPTZ` | Earliest time the dispatcher may pick this up; advanced on retries |
| `last_attempt_at` | `TIMESTAMPTZ` | Timestamp of the last processing attempt (null until first attempt) |
| `dispatched_at` | `TIMESTAMPTZ` | Set on success; non-null means the message is done |
| `delivery_attempts` | `INTEGER` | Counter incremented on each failed attempt |

An index on `(dispatched_at, available_at)` supports the pending-message query:

```sql
SELECT ... FROM admin_outbox
WHERE dispatched_at IS NULL AND available_at <= ?
ORDER BY available_at, event_id
LIMIT ?
```

## Message Lifecycle

A message passes through these states:

```
  append()           handle() succeeds
┌──────────┐       ┌──────────────────┐
│  PENDING │──────→│    DISPATCHED    │
│          │       │  dispatched_at   │
│ attempts │       │    is set        │
│   = 0    │       └──────────────────┘
└──────────┘
     │
     │ handle() fails, attempts < maxRetries
     ▼
┌────────────┐
│   RETRY    │──→ available_at advanced
│ attempts++ │    by exponential backoff
└────────────┘──→ loops back to PENDING
     │
     │ attempts >= maxRetries
     ▼
┌──────────────┐
│ COMPENSATED  │  compensate() + markFailed()
│  DISPATCHED  │  then marked dispatched
└──────────────┘
```

## Dispatching

`LifecycleEventOutboxDispatcher` is the polling engine. It is triggered by a Spring `@Scheduled` method with a fixed delay (default: 5 seconds between the end of one cycle and the start of the next, preventing overlapping polls).

Each poll cycle:

1. Fetches up to `batch.size` (default: 100) pending messages.
2. Submits each to a virtual-thread executor for parallel processing.
3. On success: calls `outboxRepository.markDispatched(...)`.
4. On failure: calls `outboxRepository.recordFailure(...)` with the next retry time.

### Exponential Backoff

Failed messages are retried with capped exponential backoff:

| Attempt | Backoff (base = 30s) | Wait |
|---------|---------------------|------|
| 1 | 30s x 1 | 30s |
| 2 | 30s x 2 | 1m |
| 3 | 30s x 4 | 2m |
| 4 | 30s x 8 | 4m |
| 5 | 30s x 16 | 8m |
| 6+ | 30s x 32 | 16m (cap) |

The exponent is clamped to 5 so backoff never exceeds `base x 32`. Arithmetic overflow falls back to a 24-hour delay.

## Handlers

### CompositeOutboxMessageHandler

Wraps multiple handlers and runs all of them for every message, even if an earlier handler throws. Exceptions are collected — the first is rethrown with subsequent ones added as suppressed causes.

The production composite contains two handlers, executed in order:

### 1. LifecycleEventOutboxHandler (read-model projection)

Runs for **every** event type. Deserializes the payload and:

- **Upserts** `mint_aggregate_snapshots` — the materialized view of current mint state.
- **Appends** to `mint_lifecycle_history` — the full audit trail.

Both operations are idempotent (upsert semantics), making repeated delivery safe.

### 2. VaultProvisioningOutboxHandler (saga step)

Reacts to specific event types:

- **`CREATED`**: Provisions vault resources (mint entity, keyset, denomination keys) via `VaultProvisioningPort`. On success, transitions the mint to `PROVISIONED`. On permanent failure, compensates and transitions to `PROVISION_FAILED`.
- **`RETIRED`**: Archives vault keysets.
- **All other events**: No-op.

## Vault Provisioning Saga

The vault provisioning saga is the primary use case for the outbox. It spans two phases:

### Phase 1 — Synchronous (within DB transaction)

```
POST /admin/lifecycle/mints
  → ManageMintLifecycleInteractor.createMint()
    → MintAggregate.create()           state = PROVISIONING
    → mintRepository.save(aggregate)
    → eventPublisher.publish(CREATED)  writes admin_outbox row
  → transaction commits (both mints row and outbox row are atomic)
  → returns {"lifecycleState": "PROVISIONING"}
```

### Phase 2 — Asynchronous (outbox dispatch)

```
Scheduler polls → finds CREATED message
  → LifecycleEventOutboxHandler: projects to read model
  → VaultProvisioningOutboxHandler:
      → vaultPort.provision(mintId, unit, denominations)
      → on success:
          aggregate.markProvisioned(audit)
          publish(VAULT_PROVISIONED)     → new outbox row
      → on failure (retriable):
          throw → dispatcher retries with backoff
      → on failure (max retries exceeded):
          vaultPort.compensate(mintId)   → best-effort cleanup
          aggregate.markProvisionFailed(audit)
          publish(VAULT_PROVISION_FAILED)
```

The next poll cycle picks up the `VAULT_PROVISIONED` (or `VAULT_PROVISION_FAILED`) message and projects it into the read model. The `VaultProvisioningOutboxHandler` ignores these event types.

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `admin.outbox.enabled` | `true` | Enable/disable the outbox scheduler |
| `admin.outbox.poll.interval` | `5000` | Milliseconds between poll cycles (fixed delay) |
| `admin.outbox.batch.size` | `100` | Max messages fetched per poll cycle |
| `admin.outbox.failure.backoff` | `PT30S` | Base backoff duration (ISO-8601) |
| `admin.vault.provision.max-retries` | `5` | Max attempts before permanent failure + compensation |

## Design Guarantees

**Atomicity.** The outbox row is written in the same transaction as the aggregate state change. There is no dual-write risk.

**At-least-once delivery.** A message may be processed more than once (e.g. crash after `handle()` but before `markDispatched()`). All handlers are designed to be idempotent: the read-model handler uses upserts, and vault provisioning treats HTTP 409 Conflict as success.

**Ordered within aggregate.** Messages for the same mint are ordered by `available_at`. Cross-aggregate ordering is not guaranteed but is not needed.

**Compensation.** If vault provisioning permanently fails, partial state is cleaned up via `vaultPort.compensate()`. Compensation is best-effort — if it fails, the error is logged but the mint still transitions to `PROVISION_FAILED`.

**Virtual threads.** Each message in a batch is dispatched on a separate virtual thread, keeping the dispatcher responsive even with slow vault calls.
