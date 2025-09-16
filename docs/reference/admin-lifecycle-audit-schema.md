# Administrative lifecycle audit schema

This reference explains the database structures backing the mint lifecycle projections.
Use it when applying migrations or building reporting queries over lifecycle activity.

## Mint aggregate snapshots (`mint_aggregate_snapshots`)

The snapshot table stores the most recent lifecycle summary for each mint. Columns:

- `mint_id` – Primary key referencing the aggregate identifier.
- `lifecycle_state` – Current [`LifecycleState`](../../cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/domain/LifecycleState.java).
- `configuration_revision_id` – Revision applied when the snapshot was taken.
- `version_tag` – Arbitrary string supplied by operators (e.g. deployment tag).
- `updated_at` – Timestamp of the lifecycle event that produced the snapshot.
- `audit_request_id` – UUID propagated from CLI/API clients to correlate the change.
- `audit_correlation_id` – External ticket or workflow identifier associated with the change.

Snapshots are updated idempotently through an UPSERT so repeated lifecycle events simply
refresh the existing row.

## Lifecycle history (`mint_lifecycle_history`)

This append-only table records every lifecycle event emitted by the aggregate.
Columns capture the audit metadata at the time of the transition:

- `event_id` – Correlation identifier shared with the transactional outbox entry.
- `mint_id` – Aggregate identifier that owns the lifecycle event.
- `event_type` – Enumerated [`MintLifecycleEventType`](../../cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/application/port/out/MintLifecycleEvent.java).
- `previous_state` / `current_state` – Lifecycle state change.
- `configuration_revision_id` – Revision id bound to the lifecycle context.
- `version_tag` – Operator supplied version string.
- `audit_*` columns – Actor, action, timestamps, reason codes, ticket references, and automation context.
- `audit_request_id` – UUID that lets downstream tooling link the event to CLI/API invocations.
- `audit_correlation_id` – Free-form string used for ticketing or workflow integration.

Query the table ordered by `audit_timestamp` to produce a chronological audit trail for a
mint. The request/correlation identifiers mirror the fields stored in the `audit_events`
log so joins remain stable across projections.

## Approval state (`mint_lifecycle_approval_states`)

Lifecycle transitions often require multi-party approval. The approval state table stores
those workflows:

- `approval_id` – Primary key (UUID) for the approval record.
- `mint_id` – Mint undergoing the transition.
- `target_state` – Lifecycle state the approval governs (e.g. `ACTIVE`).
- `approval_status` – Current status (`PENDING`, `APPROVED`, `REJECTED`, etc.).
- `required_signoffs` – JSON-encoded array of required role names.
- `granted_signoffs` / `denied_signoffs` – JSON-encoded arrays tracking reviewer decisions.
- `expires_at` – Optional expiry for time-boxed approvals.
- `audit_*` columns – Actor/action metadata for the most recent change.
- `audit_request_id` / `audit_correlation_id` – Identifiers mirroring lifecycle history entries.

Use the table to build review queues or to display approval progress alongside lifecycle
summaries. Index `idx_mint_lifecycle_approval_states_mint` supports querying approvals for
a mint and target state efficiently.

## Audit log view (`v_mint_audit_log`)

The `v_mint_audit_log` view now exposes the `request_id` and `correlation_id` columns from
`audit_events`. Consumers can join lifecycle history, approval state, and raw audit events
using these identifiers to follow a request from operator input through persistence and
notification handling.
