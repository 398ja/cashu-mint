# Task: Build CLI and REST Health Reporting Interfaces

## Background
Operators must query mint health via CLI and REST as part of Milestone M4. Interfaces should support real-time and historical data with automation-friendly outputs.

## Goal
Deliver CLI commands and REST endpoints providing health snapshots, historical trends, and incident feeds with pagination, filtering, and accessibility considerations.

## Requirements
- Implement CLI commands (`mint health`, `mint health --historical`, `mint health watch`) supporting table and JSON output with manual refresh and streaming options.
- Add REST controllers exposing endpoints for health snapshots, trend series, and incident feeds with authentication and RBAC enforcement.
- Provide presenters/serialisers ensuring consistent schemas, correlation IDs, and audit references across CLI and REST.
- Support filters for timeframe, severity, mint selection, and acknowledgement status.
- Write contract/end-to-end tests ensuring CLI and REST parity plus accessibility of CLI output (e.g., column labelling), with plain-English comments above each test method.
- Update OpenAPI specifications, CLI help text, and documentation to describe health endpoints and usage examples.

## Definition of Done
- CLI and REST interfaces return health data consistent with interactor expectations and pass tests under `mvn -q verify`.
- Automation-friendly outputs and documentation exist for scripting and dashboards.
- Accessibility and RBAC behaviours verified through tests or manual checklists.
