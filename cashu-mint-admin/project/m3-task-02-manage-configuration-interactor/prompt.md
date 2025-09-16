# Task: Implement ManageConfiguration Interactor

## Background
With the domain model supporting configuration revisions, the application layer must orchestrate submission, validation, staged approvals, apply, and rollback workflows. Milestone M3 specifies a `ManageConfiguration` interactor coordinating validators, policy engines, notifications, and persistence ports.

## Goal
Deliver an application-layer interactor (or use-case service) that encapsulates the full configuration governance lifecycle and exposes operations consumed by CLI/REST adapters.

## Requirements
- Implement a `ManageConfiguration` interactor (or equivalent use-case class) exposing operations for:
  - Submitting a new configuration revision for validation.
  - Previewing diffs/validation results without applying changes.
  - Requesting approval, recording approver decisions, and enforcing staged approval policies.
  - Applying an approved revision, including optional secrets fetch/commit steps.
  - Rolling back to a prior revision with safeguards from the domain layer.
- Coordinate schema validators, policy engines, notification dispatch, and persistence through injected ports/adapters.
- Emit domain events or application DTOs that downstream layers (CLI/REST) can present, including diff artefacts, validation reports, audit references, and next-step guidance.
- Handle error cases (validation failures, missing approvals, incompatible rollback, vault errors) with rich error types mapped later by adapters.
- Provide comprehensive unit tests covering happy paths, validation/approval failures, notification triggering, and rollback scenarios. Include clear comments above each test method per repository standards.
- Update dependency injection/module wiring so the new interactor can be resolved by adapters.

## Definition of Done
- Interactor operations are implemented with clear interfaces and port interactions aligned with Clean Architecture practices.
- All unit tests for the interactor pass and demonstrate coverage of success and failure paths.
- Interactor outputs provide the information needed for CLI/REST presenters to render diffs, validation summaries, approval states, and audit references.
- Error handling aligns with existing application layer conventions.
