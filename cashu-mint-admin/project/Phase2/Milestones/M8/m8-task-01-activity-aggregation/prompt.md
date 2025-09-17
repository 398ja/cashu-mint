# Task: Aggregate Activity Events and Build Timeline Views

## Background
The activity log must combine lifecycle, configuration, operator, and alert events with advanced filtering and bookmarking.

## Goal
Integrate audit endpoints or synthesised data to render an activity timeline with search, filters, and bookmarking capabilities.

## Requirements
- Aggregate events from `/admin/audit` or synthesised sources, normalising fields (timestamp, actor, action, mint, severity).
- Implement timeline/table views with filters for date range, action type, mint, operator, and severity.
- Provide bookmarking/saved filter capabilities for frequently used queries.
- Ensure performance through pagination/virtualisation and support keyboard navigation/accessibility.
- Write tests covering aggregation logic, filter interactions, bookmarking, and accessibility; annotate each test method with plain-English comments.
- Document event schema, filter options, and integration with other modules.

## Definition of Done
- Activity timeline renders aggregated events with responsive filtering validated via tests in `mvn verify`.
- Bookmarking and navigation features function correctly with accessible interactions.
- Documentation explains event sources, filters, and cross-module deep links.
