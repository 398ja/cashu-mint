# Task: Deliver Configuration Governance REST Endpoints

## Background
Milestone M3 expects REST automation parity with the CLI for configuration governance so that infrastructure-as-code tools can integrate with the mint. The REST layer must mirror CLI behaviours, returning machine-readable artefacts and enforcing validation/approval policies.

## Goal
Expose REST endpoints covering configuration submission, preview/diff, approval/apply, and rollback flows powered by the `ManageConfiguration` interactor.

## Requirements
- Design REST resources/routes that align with existing API conventions (versioning, authentication, error handling) while offering operations to:
  - Submit configuration revisions for validation.
  - Retrieve validation/diff previews without applying.
  - Request or record approvals.
  - Apply approved revisions.
  - Roll back to prior revisions.
- Ensure responses include diff artefacts, validation reports, audit references, and approval status in machine-consumable formats (JSON or HAL as per existing conventions).
- Enforce security requirements, including authentication/authorization checks and secure handling of secrets (coordinate with vault adapters instead of storing plain secrets).
- Map interactor errors to appropriate HTTP status codes and structured error payloads.
- Update API documentation (OpenAPI/Swagger or markdown references) to describe the new endpoints, request/response schemas, and examples.
- Provide controller/service tests (unit or integration as appropriate) that cover success, validation failure, unauthorized access, and rollback error cases. Include descriptive comments above each test method.

## Definition of Done
- REST endpoints are implemented, wired to the interactor, and follow repository API patterns.
- Automated tests for the REST layer pass and demonstrate coverage of key flows and errors.
- API documentation reflects the new endpoints with clear usage guidance.
