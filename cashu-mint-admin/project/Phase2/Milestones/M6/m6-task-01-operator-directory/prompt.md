# Task: Build Operator Directory with Filtering and Export

## Background
Milestone M6 introduces operator management requiring directory views with filtering by role, status, and mint assignment plus export capabilities.

## Goal
Implement operator list/table components with filters, pagination, and export functionality respecting policy permissions.

## Requirements
- Fetch operator lists via admin REST endpoints, normalising data for pagination, quick search, and filters (role, status, mint).
- Render table/list views with status badges, contact info, and actions for admin roles.
- Provide export options (CSV/JSON) when permitted, logging audit entries for downloads.
- Support responsive layout and accessibility guidelines for tables.
- Write component/integration tests covering filtering, pagination, export invocation, and role restrictions; annotate each test method with plain-English comments.
- Update Storybook with directory states for design review.

## Definition of Done
- Operator directory renders filtered data with export capabilities validated via tests executed in `mvn verify`.
- Role restrictions enforce read-only states for limited users.
- Documentation describes directory filters, exports, and access considerations.
