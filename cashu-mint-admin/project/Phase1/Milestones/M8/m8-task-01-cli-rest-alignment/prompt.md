# Task: Harmonise CLI and REST Interfaces

## Background
Milestone M8 focuses on interface cohesion between CLI and REST adapters, ensuring consistent DTOs, error handling, and versioning across entry points.

## Goal
Align CLI and REST responses, DTOs, and presenters across all use cases, generating OpenAPI specifications and SDKs that match CLI behaviours.

## Requirements
- Audit existing CLI commands and REST endpoints, identifying schema or error handling discrepancies.
- Refactor DTOs/presenters to share schemas and include correlation IDs, audit links, and pagination metadata consistently.
- Generate updated OpenAPI specifications and client SDKs covering all endpoints consumed by the CLI.
- Implement contract tests verifying CLI vs REST parity for representative workflows.
- Update CLI help text and REST documentation to highlight consistent behaviours and versioning strategy.
- Ensure tests include plain-English comments above each method and pass via `mvn -q verify`.

## Definition of Done
- CLI and REST share unified schemas, error semantics, and documentation with passing contract tests.
- OpenAPI specs and SDKs published, matching CLI expectations and integrated into build pipelines.
- Documentation summarises parity guarantees and upgrade guidance.
