# Task: Implement Configuration Apply and Rollback Workflows

## Background
Configuration center must support preview, apply, and rollback operations with audit prompts and incident linkage.

## Goal
Wire preview/apply/rollback flows to admin REST endpoints, capturing reasons, incident references, and handling partial success responses.

## Requirements
- Integrate preview endpoints displaying backend dry-run responses with structured summaries and warnings.
- Implement apply flow with confirmation prompts, progress indicators, and success/error messaging aligned with CLI.
- Build rollback modal requiring justification and linking to related incidents or alerts.
- Handle partial success responses by surfacing actionable follow-up tasks and logging telemetry.
- Write integration tests covering preview errors, apply success/failure, and rollback flows; annotate each test method with plain-English comments.
- Update Storybook with workflow states and documentation referencing CLI parity.

## Definition of Done
- Apply and rollback workflows function end-to-end with tests executed in `mvn verify`.
- UI captures justification, incident references, and displays backend guidance for partial results.
- Documentation explains workflow steps, telemetry, and audit considerations.
