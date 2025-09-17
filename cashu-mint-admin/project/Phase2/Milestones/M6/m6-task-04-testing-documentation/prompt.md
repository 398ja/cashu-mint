# Task: Document Operator Administration and Ensure Test Coverage

## Background
Milestone M6 must close with documentation, automation guidance, and tests covering operator management flows and audit exports.

## Goal
Publish documentation, tutorials, and tests for operator administration, including compliance exports and access restrictions.

## Requirements
- Write Diátaxis docs detailing operator onboarding/offboarding, role delegation, credential resets, and audit exports.
- Provide automation examples for onboarding/offboarding and review attestations using CLI/API parity references.
- Expand automated tests (integration, end-to-end) covering directory filters, account lifecycle, audit exports, and permission enforcement; annotate each test method with plain-English comments.
- Ensure `mvn verify` executes the new tests and fails on regressions.
- Update docs indexes and changelog to reflect operator administration availability.
- Document limitations or next steps for identity provider integrations within the web UI.

## Definition of Done
- Documentation published, linked from relevant indexes, guiding operators and compliance teams.
- Tests pass in CI, covering primary operator admin workflows and edge cases.
- Stakeholders have clarity on automation options, audit evidence, and remaining follow-up items.
