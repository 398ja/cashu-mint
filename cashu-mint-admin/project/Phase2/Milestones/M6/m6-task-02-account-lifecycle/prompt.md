# Task: Implement Operator Account Lifecycle Flows

## Background
Operators with `USER_ADMIN` role must create, update, deactivate/reactivate accounts, manage roles, and trigger credential resets.

## Goal
Build forms and dialogs handling operator creation, editing, role assignment, suspension/reactivation, and credential resets with validation.

## Requirements
- Implement create/edit forms capturing required metadata, contact details, and optional notes with validation feedback.
- Build role assignment UI using checklists from role metadata, enforcing separation-of-duties cues.
- Provide deactivate/reactivate and credential reset flows with confirmation prompts and audit capture.
- Handle API errors gracefully, surfacing actionable messaging and telemetry.
- Write integration tests covering create/update/deactivate/reactivate/reset flows, role validation errors, and audit logging; annotate each test method with plain-English comments.
- Update Storybook with form states and error scenarios.

## Definition of Done
- Account lifecycle flows operate end-to-end with tests running in `mvn verify`.
- Role assignment and validation align with backend policies, capturing audit metadata.
- Documentation describes account lifecycle steps, approval requirements, and error handling.
