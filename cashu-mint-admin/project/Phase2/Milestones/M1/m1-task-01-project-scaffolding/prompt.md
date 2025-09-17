# Task: Scaffold Frontend Project and Build Integration

## Background
Phase 2 Milestone M1 establishes the TypeScript/React SPA foundation integrated with the Maven build pipeline for the admin web experience.

## Goal
Generate the project structure, configure bundler integration with Maven, and ensure frontend assets compile during `mvn verify`.

## Requirements
- Create React/TypeScript project with feature-based directories (`dashboard`, `mints`, `configuration`, `operators`, `alerts`, `activity-log`, `settings`).
- Configure Vite/Webpack build scripts and Maven plugins to install dependencies and build bundles during `mvn verify`.
- Define environment configuration strategy (API base URLs, telemetry endpoints, feature flags) with `.env` templates.
- Ensure outputs land under `/admin/ui` for Spring Boot static serving and verify via automated build.
- Add unit smoke tests ensuring scaffolding compiles; annotate each test method with plain-English comments.
- Update `pom.xml` and build documentation describing frontend integration steps.

## Definition of Done
- Frontend project scaffolding committed with Maven integration executing automatically in `mvn verify`.
- Directory structure matches specification and compiles without manual steps.
- Documentation outlines build setup, environment variables, and integration within the monorepo.
