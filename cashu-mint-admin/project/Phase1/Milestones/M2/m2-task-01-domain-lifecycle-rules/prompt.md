# Task: Extend Domain Lifecycle Rules and Audit Metadata

## Background
Milestone M2 focuses on the ManageMintLifecycle use case. The domain layer must enforce lifecycle transitions, approval requirements, and audit context before the interactor orchestrates workflows.

## Goal
Augment `MintAggregate`, `AuditTrail`, and related value objects with lifecycle state machine rules, approval hooks, and notification associations required for create/update/pause/resume/retire actions.

## Requirements
- Finalise lifecycle state enumeration with guard clauses preventing invalid transitions or missing approvals.
- Extend `MintAggregate` to link lifecycle events with configuration revisions, notification policies, and operator metadata.
- Update `AuditTrail` entries to include lifecycle-specific fields: reason codes, ticket references, automation flags.
- Emit domain events describing lifecycle changes with payloads supporting downstream adapters.
- Cover edge cases (duplicate submissions, unauthorized roles, incompatible state sequences) via unit tests annotated with plain-English comments above each test method.
- Document lifecycle rules and approval prerequisites for future reference.

## Definition of Done
- Lifecycle rules compile with exhaustive validation and emit domain events capturing audit details.
- Unit tests under `mvn -q verify` prove allowed and rejected state transitions.
- Documentation summarises lifecycle state machine expectations and audit metadata requirements.
