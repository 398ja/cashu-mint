# Task: Build Persistent Navigation and Responsive Layout Enhancements

## Background
Milestone M3 introduces global navigation and responsive layout behaviour with role-aware visibility and accessibility requirements.

## Goal
Implement header, sidebar, breadcrumb, and status indicator components with responsive breakpoints and accessibility compliance.

## Requirements
- Develop navigation components featuring breadcrumbs, role-aware visibility, and focus management.
- Surface global connectivity and token age indicators sourced from health endpoints.
- Ensure navigation adapts between desktop and tablet breakpoints while maintaining keyboard operability and ARIA labelling.
- Integrate navigation badges for alert counts and health status signals.
- Write component tests verifying responsive behaviour, role visibility, and accessibility features with plain-English comments above each test method.
- Update Storybook with navigation states and responsive previews for design review.

## Definition of Done
- Navigation renders persistently with responsive behaviour, status indicators, and accessibility support validated via tests executed in `mvn verify`.
- Components integrated with authentication context for role visibility.
- Documentation outlines navigation structure, status indicators, and accessibility decisions.
