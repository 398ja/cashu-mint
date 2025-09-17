# Task: Prepare Release Candidate Enablement and Documentation Bundle

## Background
The final milestone wraps up with release notes, training assets, Docker/Compose verification, and end-to-end test gating.

## Goal
Compile release documentation, run final E2E/visual suites, verify Docker Compose builds with frontend assets, and deliver pilot rollout materials.

## Requirements
- Finalise Playwright/Cypress smoke suites with visual regression baselines and integrate them into CI gating.
- Run `docker-compose build` ensuring admin REST images include compiled frontend assets; document outcomes.
- Produce release notes, upgrade checklist, pilot rollout plan, and operator training materials.
- Summarise network access considerations, blocked domains, and troubleshooting guidance for pilot teams.
- Archive compliance reports (accessibility, localisation, performance) in release bundle.
- Ensure any new tests/scripts include plain-English comments and pass during `mvn verify`.

## Definition of Done
- Release candidate bundle assembled with documentation, training assets, and test evidence.
- CI gating includes final smoke/visual suites and Docker builds verified successfully.
- Stakeholders have clear rollout plan, network considerations, and support documentation.
