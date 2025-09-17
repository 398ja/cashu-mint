# Task: Model Core Domain Entities and Value Objects

## Background
Milestone M1 establishes the Clean Architecture foundation for the admin service. The domain layer must capture the key aggregates and value objects so later use cases can rely on consistent invariants and audit metadata.

## Goal
Implement the domain model for mints, configuration sets, operator accounts, audit trails, domain events, and notification policies following Clean Architecture and NUT specifications.

## Requirements
- Create immutable value objects for identifiers, lifecycle states, configuration revision IDs, policy metadata, and timestamps.
- Implement aggregate roots (`MintAggregate`, `ConfigurationSet`, `OperatorAccount`) capturing invariants for lifecycle transitions, configuration compatibility, delegation, and notification escalation.
- Embed audit context (who/what/when/why) within aggregates so interface adapters only append metadata.
- Model `AuditTrail` and `DomainEvent` hierarchies that record lifecycle, configuration, and operational events for downstream adapters.
- Ensure entities are framework-agnostic, serialisable through mappers, and validated via comprehensive unit tests.
- Add comments in plain English above every new test method per repository standards.

## Definition of Done
- Domain entities and value objects compile without framework dependencies and enforce documented invariants.
- Unit tests cover success and failure paths for transitions, approvals, and metadata requirements.
- Documentation or ADRs summarise domain modelling choices and reference relevant NUT specifications.
- `mvn -q verify` passes with the new tests.
