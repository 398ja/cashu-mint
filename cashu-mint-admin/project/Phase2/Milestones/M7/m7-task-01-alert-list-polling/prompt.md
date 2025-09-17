# Task: Implement Alert Listing with Polling and Badge Sync

## Background
The alert operations panel must display active alerts grouped by severity, update navigation badges, and poll for updates efficiently.

## Goal
Build alert list views with severity grouping, polling, manual refresh, and optimistic updates integrated with shared stores.

## Requirements
- Fetch alert feeds via admin REST endpoints with lightweight polling and manual refresh controls, respecting rate limits.
- Group alerts by severity, displaying badge counts reflected in navigation and dashboard components.
- Implement optimistic updates for acknowledgement/silence actions, reconciling with subsequent API responses.
- Support bulk acknowledgement/silence when permitted by policy.
- Write tests covering polling behaviour, badge synchronisation, optimistic updates, and permission restrictions; annotate each test method with plain-English comments.
- Update Storybook with alert list scenarios, including empty and high-severity states.

## Definition of Done
- Alert listing updates in near real-time with badge sync, validated by tests executed in `mvn verify`.
- Optimistic updates reconcile with backend state without stale data issues.
- Documentation explains polling intervals, badge behaviour, and optimistic update strategy.
