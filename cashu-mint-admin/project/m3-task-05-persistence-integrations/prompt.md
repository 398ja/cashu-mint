# Task: Implement Configuration Persistence & Integration Adapters

## Background
Milestone M3 requires persistent storage for configuration revision history, validation artefacts, approval states, and linkage to lifecycle events. The system must also integrate with schema validation engines, secure secret storage, and transactional outbox messaging for audit/notification purposes.

## Goal
Deliver infrastructure-layer adapters, migrations, and integration glue that persist configuration governance data and connect to external services mandated by M3.

## Requirements
- Design and implement database migrations creating append-only revision tables, approval state tracking, validation report storage, and active revision markers tied to `MintAggregate`.
- Update persistence adapters/repositories to read/write the enriched domain model, ensuring immutability of past revisions and safe activation/rollback semantics.
- Integrate schema validation engines (JSON Schema and any custom validators) behind ports used by the interactor; ensure validator results (including warnings/errors) are persisted for auditability.
- Wire secure secret storage/vault adapters so sensitive fields are stored/fetched via the vault instead of primary persistence.
- Configure transactional outbox messages for configuration change events, ensuring they trigger notification policies and integrate with existing messaging infrastructure.
- Update dependency injection and configuration files to register new adapters/beans and migrations.
- Provide integration and repository tests verifying migrations, persistence behaviours (append-only, rollback), validator interactions, and outbox publication. Include descriptive comments above each test method.
- Ensure observability/logging captures key audit identifiers without exposing secrets.

## Definition of Done
- Database schema supports configuration revision governance with immutable history and approval tracking.
- Persistence/adapter code integrates validators, vault, and outbox messaging per Clean Architecture boundaries.
- Tests pass demonstrating reliable persistence behaviours and integration flows.
- Configuration files or environment docs updated to describe new dependencies (e.g., validator schemas, vault configuration, messaging topics).
