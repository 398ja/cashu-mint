# Task: Extend Configuration Governance Domain Model

## Background
Milestone M3 requires the platform to support configuration lifecycle governance with revision history, validation artefacts, and approval checkpoints. The current domain entities (`ConfigurationSet`, `MintAggregate`, `NotificationPolicy`, etc.) do not yet encode these behaviours.

## Goal
Update the enterprise business rules so the domain can represent configuration revisions, compute diffs, capture validation/approval metadata, and reference notification policies for lifecycle workflows.

## Requirements
- Enhance `ConfigurationSet` (and related aggregates) to:
  - Track immutable revision history with ordering metadata (author, timestamps, revision state).
  - Compute diffs between revisions and against the currently active mint configuration.
  - Enforce compatibility checks/safeguards required before rollback or apply operations.
  - Persist validation reports and approval artefacts for audit replay.
- Associate configuration revisions with `MintAggregate` and `NotificationPolicy` metadata so approval/notification workflows can reference configuration state.
- Define domain events/value objects needed by later layers (interactor, adapters) to reason about submissions, approvals, and rollbacks.
- Ensure secret/sensitive fields are modelled so that adapters can hand them off to secure storage rather than persisting them in clear text.
- Provide comprehensive unit tests for the domain layer, covering positive flows, validation failures, diff computation edge cases, and rollback safeguards. Add descriptive comments above each test method per repository guidelines.
- Update any relevant documentation or architecture notes within the domain module if such files exist and require adjustment (e.g., aggregate diagrams).

## Definition of Done
- Domain model supports revision history, diff computation, validation/approval metadata, and linkage to mint lifecycle entities.
- Tests validating the new behaviours are in place and passing.
- No secrets are left in domain persistence structures; instead, they are abstracted behind domain concepts for vault adapters.
- Changes align with the Clean Architecture boundaries already present in the codebase and are ready for application layer orchestration.
