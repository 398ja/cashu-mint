# Task: Implement Activity Log Export and Reporting Tooling

## Background
Operators must export activity data in CSV/JSON formats with progress indicators, telemetry, and audit logging.

## Goal
Provide export utilities, progress feedback, error handling, and telemetry for activity log reporting while respecting role-based visibility.

## Requirements
- Build export workflows allowing users to select date range, action types, mints, and operators, supporting CSV/JSON output.
- Implement progress indicators and cancel/retry controls for long-running exports.
- Ensure exported data includes correlation IDs, audit metadata, and respects permission filtering.
- Log export attempts to telemetry, capturing success/failure metrics for capacity planning.
- Write tests covering export initiation, progress updates, permission filtering, and telemetry logging; annotate each test method with plain-English comments.
- Document export procedures, security considerations, and integration with compliance tooling.

## Definition of Done
- Activity exports operate reliably with progress feedback, validated by tests in `mvn verify`.
- Telemetry and audit logs capture export usage and errors for monitoring.
- Documentation guides operators through export workflows and compliance usage.
