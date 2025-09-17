# Task: Implement Role-aware Routing and Navigation Guards

## Background
Authenticated operators require role selection, guarded routes, and navigation visibility that respects permissions defined by the admin REST contract.

## Goal
Enforce role-based access control in the frontend by integrating role selectors, guarded routes, and navigation visibility tied to authentication context.

## Requirements
- Fetch available roles per operator after authentication and expose selector controls in the global header.
- Guard React Router routes based on required roles, rendering informative empty states when access is denied.
- Apply role-based visibility to navigation items, quick actions, and component-level controls.
- Ensure API client wrappers automatically include `X-Admin-Token` and `X-Admin-Roles` headers.
- Write unit/integration tests covering role switching, guard rendering, and restricted action messaging with plain-English comments above each test method.
- Document role guard patterns for developers, including how to protect new routes and actions.

## Definition of Done
- Role-aware routing and navigation behaviours implemented with tests executed via `mvn verify`.
- Operators can switch roles, see permitted modules, and receive clear messaging for restricted areas.
- Documentation outlines role guard usage, selectors, and API header requirements.
