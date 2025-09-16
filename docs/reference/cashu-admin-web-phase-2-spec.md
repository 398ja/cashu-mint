# Cashu Admin Web Interface Phase 2 Specification

## Introduction
The Cashu admin web interface (phase 2) delivers a browser-based control plane for operators to provision, configure, and monitor mint instances without relying on the command-line tooling introduced in phase 1. This specification captures the functional scope, user experience expectations, integration touchpoints, non-functional requirements, and delivery roadmap needed to implement the web client that sits on top of the existing admin REST API and lifecycle projections.

## Scope and goals
- Provide a responsive, role-aware web experience for the workflows currently covered by the admin CLI and REST surfaces.
- Maintain feature parity with the lifecycle, configuration, operator management, and alerting flows backed by the `cashu-mint-admin-rest` service while improving discoverability and cross-linking between areas.
- Deliver an extensible front-end architecture that can incorporate future observability modules (metrics, usage reports) without rewrites.
- Offer safer, auditable changes by surfacing preview states, revision history, and confirmation prompts before performing irreversible actions.

### Non-goals
- Replacing or extending the existing REST endpoints beyond the documented contract; phase 2 consumes APIs exposed in [`cashu-mint-admin-rest`](../../cashu-mint-admin-rest).
- Implementing real-time push channels for alerts or metrics; polling and manual refresh cover the initial release.
- Building customer-facing wallet interactions; the interface remains limited to internal mint operations.

## Stakeholders and user roles
| Stakeholder | Role(s) | Primary objectives |
| --- | --- | --- |
| Mint operators | `MINT_ADMIN` | Provision and lifecycle-manage mints, inspect health, and coordinate maintenance windows. |
| User administrators | `USER_ADMIN` | Create, update, and deactivate operator accounts while delegating appropriate roles. |
| Alert responders | `ALERTS_ADMIN` | Investigate, acknowledge, silence, and escalate operational alerts triggered by the mint. |
| Compliance/audit staff | Read-only (composite) | Review lifecycle decisions, configuration revisions, and operator changes for traceability. |
| Platform engineers | Multi-role | Maintain the platform, manage tokens, and ensure deployments stay healthy. |

## Phase 2 deliverables overview
1. **Unified dashboard** summarising mint status, open alerts, recent lifecycle actions, and pending configuration drafts.
2. **Mint lifecycle workspace** covering create, update, pause, resume, and retire flows with preview and confirmation steps.
3. **Configuration management center** supporting preview/apply/rollback interactions with revision diffs.
4. **Operator administration module** for account CRUD, role assignment, and credential reset triggers.
5. **Alert operations panel** aggregating active alerts, history, and escalation actions.
6. **Activity and audit log viewer** with filters by action type, mint, and operator.
7. **Settings area** for API token rotation guidance, client preferences (theme, table density), and profile management.

## Functional requirements

### Navigation and layout
- Provide persistent global navigation exposing Dashboard, Mints, Configuration, Operators, Alerts, and Activity Log sections.
- Display contextual breadcrumbs and secondary navigation for mint-scoped pages.
- Surface global status indicators (API connectivity, auth token age) in the header.

### Dashboard
- Aggregate key metrics per mint: lifecycle state, current configuration revision, outstanding alerts, and recent change log entries.
- Allow filtering by mint or role scope, defaulting to the operator's most-used mint.
- Show quick actions for create mint, invite operator, and acknowledge high-severity alerts.

### Mint lifecycle workspace
- List all mint instances with sortable columns (state, version tag, last change, owner team).
- Provide detail views showing metadata, lifecycle history, and linked configuration revisions.
- Support create, update, pause, resume, and retire actions with multi-step dialogs:
  - Step 1: Input metadata and justification.
  - Step 2: Preview response payload from `POST /admin/lifecycle` endpoints.
  - Step 3: Confirmation with audit summary and optional notification recipients.
- Enforce role-based access by disabling or hiding actions for operators lacking `MINT_ADMIN`.

### Configuration management center
- Retrieve current parameters and revision history from `/admin/configuration` endpoints.
- Provide visual diffs between revisions (JSON diff view with collapsible sections).
- Allow drafting changes in a form-driven editor with validation and YAML/JSON toggle.
- Offer explicit preview action before apply, rendering the API's preview response inline.
- Support rollback with requirement to document the reason and link to related incidents.

### Operator administration module
- Display all operators with filters for role, status, and mint assignment.
- Support creation, update, role changes, credential reset, and deactivate flows mirroring `/admin/users` endpoints.
- Provide audit timeline per operator (recent actions taken, login activity if available).
- Enforce `USER_ADMIN` role for mutating actions.

### Alert operations panel
- Show active alerts grouped by severity, with badge counts in navigation.
- Provide acknowledgement, silence, unsilence, and escalation controls per alert.
- Surface historical alerts with filters by timeframe, severity, and mint.
- Display alert details including originating subsystem, timestamps, and linked runbooks.

### Activity and audit log viewer
- Aggregate lifecycle, configuration, user, and alert actions into a single timeline view.
- Offer column-level export (CSV/JSON) scoped by date range and action type.
- Support deep links from other modules (e.g., clicking a revision opens the timeline filter).

### Settings and preferences
- Show the currently active admin token and rotation schedule (read-only).
- Allow operators to adjust theme (light/dark), table density, and default landing page stored in local preferences.
- Provide contextual help links to Diátaxis docs relevant to each module.

### Cross-cutting behaviours
- Provide optimistic UI states with loading indicators and inline error banners per request.
- Persist unsaved form changes locally to protect against accidental navigation.
- Respect role headers in each request and surface missing role errors with actionable guidance.

## Information architecture and data model alignment
- Treat mints as the primary entity; configuration revisions, alerts, and lifecycle actions reference a mint identifier.
- Maintain client-side caching keyed by `mintId`, `revisionId`, `alertId`, and `userId` to reduce redundant fetches.
- Normalize API responses into shared stores (e.g., React Query/RTK Query) to support cross-module linking.
- Map REST DTOs (e.g., `LifecycleActionResponse`, `UserResponse`, `AlertActionResponse`) to typed client models with codecs for runtime validation.

## API integration plan
| UI module | REST endpoints | Notes |
| --- | --- | --- |
| Dashboard | `GET /admin/lifecycle/mints`, `GET /admin/alerts`, `GET /admin/configuration/mints/{mintId}/revisions` | Use lightweight polling (60s) for alerts when viewing dashboard. |
| Mint lifecycle | `POST /admin/lifecycle/mints`, `PUT /admin/lifecycle/mints/{mintId}`, `POST /admin/lifecycle/mints/{mintId}/pause`, `.../resume`, `.../retire` | Preview step issues dry-run calls when available; otherwise, reuse response payload for confirmation. |
| Configuration | `POST /admin/configuration/mints/{mintId}/preview`, `.../apply`, `.../rollback`, `GET` history endpoints | Ensure preview responses are cached per draft revision for diff view. |
| Operators | `POST /admin/users`, `PUT /admin/users/{userId}`, `POST /admin/users/{userId}/roles`, `.../reset-credentials`, `.../deactivate` | Role assignment UI should prefetch available role constants. |
| Alerts | `POST /admin/alerts`, `POST /admin/alerts/{alertId}/acknowledge`, `.../silence`, `.../unsilence`, `.../escalate` | When acknowledging, update optimistic state and reconcile with API response. |
| Activity log | Future `/admin/audit` endpoint or aggregation via existing responses | If endpoint is unavailable, phase 2 ships with aggregated data derived client-side from existing calls. |

All administrative requests must attach `X-Admin-Token` and `X-Admin-Roles` headers, using the operator's session token and selected role scope. Session management should persist these values securely in memory or encrypted storage scoped to the browser session.

## User experience and interaction design
- Adopt a responsive, grid-based layout that supports desktop (≥1280px) and tablet (≥768px) breakpoints. Mobile browsers may receive a read-only subset in phase 2 but should not block layout rendering.
- Use a component library with accessible primitives (e.g., Material UI, Chakra UI) or a custom design system matching Cashu branding.
- Provide consistent modal patterns for confirmational actions, including summary of changes and warnings.
- Include inline contextual help popovers referencing existing documentation (e.g., lifecycle how-to guides).
- Ensure keyboard navigation for critical flows (Tab order, skip links, focus management) and descriptive aria labels for control groups.

## Security and access control
- Enforce authentication via the admin token; no anonymous access is allowed.
- Require operators to select one or more roles per session; available actions filter automatically based on role selection.
- Obscure sensitive fields (tokens, credential reset codes) by default with reveal toggles and clipboard-copy tracking.
- Time out inactive sessions after 15 minutes of inactivity with warning prompt and safe preservation of drafts.
- Log all API interactions with correlation IDs to support audit investigations.

## Non-functional requirements
- **Performance:** Initial load (including authentication round-trip) must complete within 3 seconds on a 3G-fast network; subsequent navigation should reuse cached data and stay under 1 second.
- **Reliability:** Handle API errors gracefully with retry options and fallback messaging; highlight partial failures when multi-step workflows involve multiple calls.
- **Accessibility:** Target WCAG 2.1 AA compliance; ensure contrast ratios, ARIA landmarks, and screen reader announcements for state changes.
- **Internationalisation:** Externalise copy to translation files and support locale switching (English default). Numeric and date formats follow the operator's locale.
- **Auditability:** Capture who initiated each action, the payload summary, and resulting response message; expose this metadata in the activity log.
- **Browser support:** Latest stable versions of Chrome, Firefox, Safari, and Edge. Degrade gracefully on older versions without ES2020 support by displaying a compatibility warning.

## Client architecture
- Implement the interface as a standalone single-page application (SPA) bundled with Vite or Webpack and deployed alongside the admin REST service via the existing Spring Boot static resources pipeline.
- Structure the codebase by feature modules (e.g., `dashboard`, `mints`, `configuration`, `operators`, `alerts`, `activity-log`, `settings`) with shared UI primitives under a `components` package and API hooks under `services`.
- Use TypeScript for type safety and to mirror DTO contracts; define generated API clients from the OpenAPI spec when available.
- Manage server state with React Query (or equivalent) to handle caching, background refetching, and request deduplication.
- Centralise routing using React Router with guard components that enforce role checks before rendering protected routes.

## State persistence and offline considerations
- Store authentication tokens and role selections in `sessionStorage` with optional `localStorage` opt-in for "remember me" sessions.
- Persist unsaved drafts (configuration changes, lifecycle forms) in `localStorage` scoped by mint and operator ID; clear on successful submission.
- Provide read-only fallback views when offline, showing last-synced data with prominent banners and disabled mutation controls.

## Telemetry and observability
- Emit front-end telemetry (page views, key actions, API latency) to the existing monitoring stack (e.g., OpenTelemetry via OTLP exporter).
- Correlate UI events with backend logs by forwarding `x-request-id` headers and including them in client logs for support.
- Capture uncaught errors and rejected promises, forwarding them to the observability platform with scrubbed sensitive data.

## Testing strategy
- Unit tests for UI components, hooks, and utilities using Jest and Testing Library with comprehensive coverage of edge cases.
- Integration tests exercising full workflows (e.g., create mint, apply configuration) with mocked REST responses.
- End-to-end (E2E) smoke tests using Playwright or Cypress against a local deployment of `cashu-mint-admin-rest` to validate role-based access and regression coverage.
- Accessibility audits with axe-core and manual keyboard testing during CI.
- Visual regression testing on core pages (dashboard, mint detail, configuration diff) to catch unintended UI shifts.

## Deployment and release management
- Package the SPA as static assets served by the admin REST module under `/admin/ui`.
- Integrate build steps into the Maven lifecycle using the frontend-maven-plugin or Gradle wrapper invoked from Maven modules.
- Provide Docker image updates that include the compiled assets; verify via `docker-compose build` before release.
- Document configuration for hosting behind reverse proxies, including cache-control headers and CSP settings.

## Rollout and implementation roadmap
1. **Foundation (Sprint 1-2):** Set up project scaffolding, routing, authentication guard, and baseline theming. Deliver skeleton Dashboard pulling mint list.
2. **Lifecycle and configuration (Sprint 3-4):** Implement mint lifecycle flows, configuration editor with preview, and revision history views.
3. **Operator and alerts (Sprint 5-6):** Complete operator management and alert handling modules with optimistic updates.
4. **Activity log and settings (Sprint 7):** Build aggregated timeline view, export capabilities, and user preferences panel.
5. **Hardening (Sprint 8):** Accessibility fixes, cross-browser testing, telemetry wiring, and documentation updates.
6. **Release candidate (Sprint 9):** Freeze features, complete E2E tests, produce release notes, and run pilot with select operators.

## Risks and mitigations
- **API gaps:** Some audit data may be unavailable; coordinate with backend team to expose `/admin/audit` endpoints or adjust scope with derived data.
- **Role complexity:** Operators with multiple roles may experience confusing action availability; introduce a role switcher with explanatory tooltips.
- **Configuration diffs readability:** JSON diffs may overwhelm users; provide structured views and search within large payloads.
- **Token management:** Storing admin tokens in the browser carries risk; enforce short-lived sessions and encourage token rotation via reminders.

## Open questions
- Should the dashboard surface real-time websocket updates in a later phase? Determine backend readiness before locking architecture.
- What branding assets (logos, colours) are approved for the UI theme? Await design sign-off.
- Is multi-factor authentication planned for admin access? Specification assumes token-based auth until MFA requirements are defined.
- Will operator impersonation or read-only sharing links be required? Not included in phase 2 scope pending stakeholder input.

