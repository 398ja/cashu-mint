# Task: Implement Application Shell, Routing, and Shared Providers

## Background
Milestone M1 must deliver the SPA layout shell, navigation placeholders, and shared providers to support future feature modules.

## Goal
Create the root layout, header/sidebar, React Router configuration, and shared providers (theme, query client, telemetry) required for the admin web app.

## Requirements
- Implement responsive layout components (header, sidebar, content area) following UX guidelines, with placeholder navigation for feature modules.
- Configure React Router with guarded routes that defer rendering until authentication context is available.
- Stub shared providers (theme, React Query client, telemetry) and ensure they are extendable for later milestones.
- Ensure accessibility (skip links, focus outlines) and keyboard navigation for layout components.
- Write unit tests for layout, routing, and provider composition with plain-English comments above each test method.
- Update Storybook or component preview to showcase the layout shell and navigation states.

## Definition of Done
- Application shell renders placeholder routes for each feature pillar with responsive behaviour and accessibility support.
- Shared providers initialised and ready for future milestones, validated via tests run in `mvn verify` (frontend step).
- Documentation describes routing strategy, layout architecture, and provider responsibilities.
