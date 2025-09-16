# Cashu Admin Phase 2 Technical Analysis

This explanation documents the technical analysis for the Cashu admin web interface phase 2 initiative. It distils the functional specification, UX deliverables, and roadmap into implementation guidance for engineers integrating the single-page application with the existing admin services.

## Scope alignment

Phase 2 delivers a browser-based control plane that matches the administrative coverage achieved by the command-line tooling and REST surfaces introduced in phase 1. The scope defined in the [functional specification](../../cashu-mint-admin/project/Phase2/cashu-admin-web-phase-2-spec.md) covers seven feature pillars:

1. Unified dashboard consolidating mint state, alerts, and recent lifecycle activity.
2. Mint lifecycle workspace for create, update, pause, resume, and retire workflows.
3. Configuration management center with preview, apply, rollback, and diff tooling.
4. Operator administration module supporting account and role management.
5. Alert operations panel to acknowledge, silence, escalate, and review incidents.
6. Activity and audit log viewer integrating lifecycle, configuration, and user actions.
7. Settings surface for token hygiene and operator preferences.

The UX wireframes, mockups, and UI guidelines provide responsive layouts, accessibility targets, and visual language that the implementation must respect. Each module must maintain parity with the `cashu-mint-admin-rest` API without extending the backend contract.

## Client architecture and technology selection

Phase 2 standardises on a TypeScript-based single-page application bundled with Vite or Webpack and delivered by the admin REST service. Feature-based directory boundaries (dashboard, mints, configuration, operators, alerts, activity-log, settings) align with the specification and simplify ownership. Shared primitives should be abstracted into reusable component and service packages to enforce consistency between modules.

State management must combine React Query (or an equivalent cache-aware data layer) with local component state for presentation logic. React Router guards should enforce role availability before route activation, preventing unauthorised rendering paths. Front-end telemetry needs to integrate with the existing OpenTelemetry pipeline to correlate UI events with backend traces via propagated correlation identifiers.

## API integration model

All administrative interactions traverse the `cashu-mint-admin-rest` interface using the operator's `X-Admin-Token` and `X-Admin-Roles` headers. The following cross-module patterns emerge:

- **Lifecycle and configuration flows** reuse preview endpoints to surface the backend's dry-run payload prior to confirmation. Optimistic state updates can proceed once responses are validated and cached by mint identifier.
- **Operator management** requires prefetched role metadata to render checklists and enforce validation when adjusting privileges. Audit timelines should aggregate the responses exposed by user detail requests.
- **Alerts and dashboard widgets** benefit from lightweight polling (60 seconds by default) paired with manual refresh affordances. Shared selectors should normalise alert entities so that acknowledgement changes propagate instantly across cards and tables.
- **Activity log** consolidates lifecycle, configuration, and operator events. If the dedicated `/admin/audit` endpoint remains unavailable, the client should synthesise entries from existing API responses while tagging records with their originating module for traceability.

Local persistence is limited to session and local storage for authentication context and draft recovery. Tokens must default to session storage, with explicit user opt-in for longer-lived storage.

## Role-based access control considerations

The interface must respect the roles enumerated in the specification:

- `MINT_ADMIN` governs lifecycle operations per mint, including pause/resume and retirement dialogs.
- `USER_ADMIN` enables operator onboarding, updates, role assignment, and credential resets.
- `ALERTS_ADMIN` unlocks acknowledgement, silence, escalation, and history inspection workflows.
- Read-only compliance roles aggregate view permissions but cannot invoke mutating actions.

Role selection should appear as a global control, backed by secure storage of the active scope. Guard components and disabled state patterns prevent accidental initiation of actions outside the operator's assigned roles. Audit views should annotate which role executed each operation to help compliance teams differentiate between multi-role operators.

## Non-functional requirements

The release must satisfy the non-functional envelope described in the specification:

- **Performance:** cold starts complete within three seconds on 3G-fast networks, while cached navigation remains below one second. Component-level code splitting combined with query caching minimises redundant payloads.
- **Reliability:** every asynchronous workflow displays loading indicators, error banners, and retry affordances. Batched mutations should surface partial failures per sub-request.
- **Accessibility:** WCAG 2.1 AA conformance requires semantic headings, focus management, labelled form inputs, and contrast-compliant theming inherited from the design system.
- **Internationalisation:** copy must externalise into locale dictionaries, ensuring number and date formatting honours operator locale preferences.
- **Security:** inactivity timeouts, sensitive field masking, and confirmation prompts mitigate accidental exposure. Session expiry warnings allow operators to save drafts before enforced logout.

## Testing and quality strategy

The testing expectations blend several layers:

- Unit coverage for UI components, hooks, and utilities using Jest with Testing Library.
- Integration suites that orchestrate end-to-end flows (e.g., mint creation, configuration rollback) backed by mocked REST adapters.
- Playwright or Cypress smoke tests validating role enforcement and regression coverage against a deployed admin REST instance.
- Automated accessibility scans (axe-core) plus manual keyboard navigation checks.
- Visual regression snapshots focusing on dashboard summaries, diff visualisations, and alert panels.

CI pipelines must incorporate these suites, treating accessibility and visual baselines as gating checks ahead of release candidate milestones.

## Deployment pipeline updates

The SPA build should integrate into the Maven lifecycle through the frontend-maven-plugin or an equivalent wrapper, ensuring reproducible builds during `mvn verify`. Generated assets must publish alongside the admin REST resources under `/admin/ui`. Docker images and Compose stacks require rebuild verification so that static assets propagate to runtime environments. Content security policies should be defined to restrict script origins and protect telemetry endpoints.

## Risk outlook and mitigations

Key delivery risks identified in the specification translate into the following mitigations:

- Coordinate early with backend maintainers to prioritise the `/admin/audit` endpoint or design robust client-side synthesis for interim auditing.
- Provide role education through inline tooltips and empty-state messaging to reduce confusion for multi-role operators switching between modules.
- Enhance configuration diff usability with collapsible sections, search, and syntax highlighting for large payloads.
- Reinforce token hygiene via short-lived storage, rotation reminders, and clipboard tracking for sensitive values.

Addressing these items during the foundation and hardening sprints helps de-risk the release candidate timeline defined for sprint 9.
