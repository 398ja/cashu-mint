# Task: Deliver Notification Management CLI/REST Interfaces

## Background
Administrators must manage alert rules, recipients, silencing windows, and acknowledgements via CLI and REST with role-based visibility.

## Goal
Implement CLI commands and REST endpoints covering rule CRUD, testing, silencing, acknowledgement, and escalation workflows with consistent schemas.

## Requirements
- Build CLI commands (`mint alerts create/list/silence/ack/test`) supporting scripting, channel previews, and suppression scheduling.
- Add REST endpoints for the same operations with RBAC enforcement, pagination, and filter support.
- Provide presenters for rule summaries, delivery history, and acknowledgement status with correlation IDs and audit references.
- Ensure optimistic updates keep dashboard badge counts in sync when actions occur.
- Write contract/end-to-end tests validating CLI/REST parity, RBAC, optimistic updates, and error messaging with plain-English comments above each test method.
- Update OpenAPI specs, CLI help, and documentation describing notification workflows and automation recipes.

## Definition of Done
- CLI and REST notification interfaces operate end-to-end with consistent behaviour and pass tests under `mvn -q verify`.
- UI/automation consumers can manage rules, silences, and acknowledgements with accurate state reflections.
- Documentation guides teams through alert governance and integration patterns.
