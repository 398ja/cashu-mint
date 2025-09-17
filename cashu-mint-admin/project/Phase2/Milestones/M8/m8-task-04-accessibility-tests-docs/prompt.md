# Task: Document Activity and Settings Modules with Accessibility and i18n Validation

## Background
Milestone M8 closes with documentation and quality assurance for activity and settings modules, including accessibility and internationalisation readiness.

## Goal
Publish documentation, run accessibility/i18n checks, and expand tests covering activity filters, exports, and settings preferences.

## Requirements
- Execute accessibility audits (axe-core, keyboard navigation) and remediate findings for activity and settings components.
- Externalise copy where feasible, adding localisation hooks and verifying locale switching coverage.
- Extend automated tests (integration, end-to-end) covering activity filters, exports, settings persistence, and locale toggles; annotate each test method with plain-English comments.
- Write Diátaxis docs for activity log usage, export processes, and settings management, linking to security guidance.
- Ensure tests run in `mvn verify` and fail on regressions, including accessibility/i18n checks.
- Update docs indexes and changelog summarising activity/settings milestone status.

## Definition of Done
- Accessibility and i18n validations pass with documented remediation and locale readiness.
- Documentation published, guiding operators through activity and settings features.
- Tests cover key workflows and run successfully in CI.
