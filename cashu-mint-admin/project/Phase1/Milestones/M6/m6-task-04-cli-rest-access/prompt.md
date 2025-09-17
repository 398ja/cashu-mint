# Task: Deliver Access Administration CLI/REST Interfaces and Audit Exports

## Background
Administrators must manage users and roles via CLI and REST while exporting audit evidence for compliance. Interfaces need to mirror the AdministerAccess interactor and integrate with identity providers.

## Goal
Implement CLI commands and REST endpoints for user provisioning, role management, suspension/reactivation, credential rotation, and audit export with consistent schemas and permissions.

## Requirements
- Build CLI commands (`mint users add/suspend`, `mint roles grant/revoke`, `mint audit export`) producing machine-readable output for compliance tooling.
- Implement REST endpoints covering the same operations with RBAC enforcement, pagination, and filtering capabilities.
- Provide presenters/serialisers for audit logs supporting CSV/JSON export with tamper-evident metadata.
- Integrate with identity adapters for token rotation and group sync when actions occur.
- Write end-to-end or contract tests verifying CLI/REST parity, export integrity, and permission handling with plain-English comments above each test method.
- Update documentation/OpenAPI specs describing endpoints, export formats, and automation patterns.

## Definition of Done
- CLI and REST interfaces manage access end-to-end with audit exports validated by tests running in `mvn -q verify`.
- Exports include correlation identifiers, signatures, and retention guidance.
- Documentation guides operators through onboarding/offboarding, audit reviews, and automation workflows.
