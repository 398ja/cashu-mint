# Task: Implement CLI and REST Operational Control Interfaces

## Background
Operators require CLI and REST workflows to plan, execute, monitor, and cancel operational jobs. Milestone M5 demands cohesive UX with confirmation prompts, scheduling options, and progress displays.

## Goal
Deliver CLI commands and REST endpoints for operational controls, ensuring schema parity, RBAC, and integration with notification hooks.

## Requirements
- Build CLI commands (`mint ops plan`, `mint ops execute`, `mint ops status`, `mint ops cancel`) with scheduling options, confirmation prompts, and streamed progress output.
- Implement REST endpoints mirroring CLI features, secured via JWT and role enforcement, returning machine-readable responses.
- Provide presenters/serialisers that expose progress tokens, log excerpts, and audit references for integration with ticketing systems.
- Handle long-running polling and pagination of execution history for both CLI and REST clients.
- Write contract/end-to-end tests verifying CLI/REST parity, RBAC enforcement, and progress streaming with plain-English comments above each test method.
- Update OpenAPI specs, CLI help, and documentation describing operational workflows and automation examples.

## Definition of Done
- CLI and REST operational interfaces function end-to-end with consistent schemas and pass tests under `mvn -q verify`.
- Operators can monitor job progress, retrieve artefacts, and cancel operations as needed.
- Documentation guides users through scheduling, executing, and monitoring operations from CLI and REST.
