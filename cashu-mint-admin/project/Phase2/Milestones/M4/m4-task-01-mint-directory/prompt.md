# Task: Implement Mint Directory and Detail Views

## Background
Milestone M4 requires list/detail views showing mint lifecycle history, configuration links, and contextual actions for operators with `MINT_ADMIN` role.

## Goal
Build sortable/filterable mint tables, detail pages with lifecycle timelines, and integration with deep links from other modules.

## Requirements
- Fetch mint lists via shared data hooks, providing sorting, filtering, quick search, and pagination.
- Display tables with state, version, owner team, and last change timestamp plus status badges.
- Implement mint detail pages summarising metadata, lifecycle history, configuration revisions, and related alerts.
- Support deep links from dashboard and quick actions, maintaining breadcrumbs and context.
- Write component/integration tests covering sorting, filtering, detail rendering, and role guards; annotate each test method with plain-English comments.
- Update Storybook with list/detail scenarios for UX validation.

## Definition of Done
- Mint directory and detail views render live data with role-based access and pass tests executed in `mvn verify`.
- Deep links function correctly, preserving navigation context and accessibility.
- Documentation explains directory filters, detail content, and navigation patterns.
