# Task: Model Telemetry Thresholds and Health Events

## Background
Milestone M4 introduces the MonitorMintHealth use case. The domain layer must describe telemetry thresholds, acknowledgement policies, and health events before application services can evaluate and surface status.

## Goal
Extend domain entities to capture telemetry configurations, health status snapshots, and incident events linked to mints, configurations, and notification policies.

## Requirements
- Add value objects for telemetry thresholds, acknowledgement policies, and data attribution (mint + configuration version).
- Model health incident events and status snapshots that correlate metrics with lifecycle and configuration context.
- Ensure invariants prevent orphaned telemetry data and enforce attribution to valid mints.
- Generate domain events for threshold breaches, acknowledgements, and recovery transitions.
- Create unit tests covering threshold evaluation rules, event creation, and attribution checks with plain-English comments above each test method.
- Document telemetry domain concepts for downstream teams.

## Definition of Done
- Domain layer supports telemetry configuration, status snapshots, and health events with enforced invariants.
- Tests run via `mvn -q verify` validating domain behaviours.
- Documentation summarises telemetry-related entities and event semantics.
