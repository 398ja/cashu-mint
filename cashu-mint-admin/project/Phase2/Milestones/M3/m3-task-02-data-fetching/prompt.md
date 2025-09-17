# Task: Establish Shared Data Fetching and State Management Layer

## Background
The dashboard and future modules require reusable data fetching utilities, caching, and polling behaviours built on React Query (or equivalent).

## Goal
Configure query clients, API wrappers, codecs, and polling utilities shared across the web app, handling errors and retries gracefully.

## Requirements
- Initialise React Query client with sensible defaults (stale times, retry policies, error boundaries) and wrap the app with providers.
- Implement typed API wrappers/codecs for mint list, alert list, lifecycle summaries, and supporting endpoints.
- Add background polling with manual refresh and exponential backoff on failure, including stale data messaging.
- Expose hooks/services for reuse by dashboard and subsequent modules with thorough documentation.
- Write unit/integration tests covering API wrappers, error handling, and polling behaviours; annotate each test method with plain-English comments.
- Instrument telemetry for API calls, including latency metrics and failure counts.

## Definition of Done
- Shared data layer accessible to dashboard and future features, validated by tests run in `mvn verify`.
- Polling, retries, and telemetry behaviours operate according to specification.
- Documentation explains query architecture, hook usage, and error handling patterns.
