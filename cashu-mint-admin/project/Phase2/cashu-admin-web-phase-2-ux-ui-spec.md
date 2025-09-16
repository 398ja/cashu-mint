# Cashu Admin Web Interface Phase 2 UX/UI Specification

## Overview
### Project goals
- Deliver a trustworthy, browser-based control plane that extends phase 1 admin capabilities with visually guided workflows and contextual validation.
- Improve operator efficiency by centralising lifecycle, configuration, alerts, and audit tooling within a cohesive navigation system.
- Support future feature growth (metrics, reporting) without disruptive redesigns by defining scalable layouts and reusable UI primitives.

### Audience
- **Mint operators (`MINT_ADMIN`)** who provision, pause, resume, and retire mints while monitoring operational health.
- **User administrators (`USER_ADMIN`)** responsible for onboarding and managing operator accounts, role assignments, and credential resets.
- **Alert responders (`ALERTS_ADMIN`)** triaging incidents, coordinating mitigations, and acknowledging or silencing alerts.
- **Audit and compliance reviewers** who need read-only visibility into lifecycle decisions, configuration revisions, and operator actions.
- **Platform engineers** maintaining the infrastructure, managing tokens, and ensuring deployments stay aligned with release standards.

### Design principles
- **Role-aware clarity:** Surface the most relevant actions and data for the operator's active role while reducing noise from inaccessible features.
- **Progressive disclosure:** Keep complex workflows manageable through step-by-step dialogs, inline previews, and contextual help popovers.
- **Consistency and predictability:** Align copy, iconography, and layout across modules so learned behaviors carry over.
- **Resilience first:** Provide robust loading, error, and empty states that preserve drafts and guide recovery without data loss.
- **Accessible responsiveness:** Target WCAG 2.1 AA, support keyboard and assistive technology users, and adapt gracefully from tablet to widescreen workstations.

## User flows
Each flow references existing REST endpoints described in the [phase 2 functional specification](./cashu-admin-web-phase-2-spec.md).

### Dashboard triage
```
Operator selects role → Dashboard loads mint summaries → User filters by mint/severity → Operator opens critical alert → Operator acknowledges or escalates → Dashboard refreshes status cards
```

### Create mint lifecycle
```
Dashboard → “Create mint” CTA → Step 1 form (metadata, justification) → Step 2 preview (dry-run payload) → Step 3 confirmation (roles notified) → Success toast → Redirect to mint detail with pending status
```

### Update configuration with review
```
Mint detail → Configuration tab → “Draft changes” → Form editor with validation → Preview API call results → Diff comparison review → Apply changes with reason → Success banner → Activity log entry linked
```

### Operator onboarding
```
Operators list → “Invite operator” dialog → Enter identity + roles → Send invitation → Confirmation with copyable invite link → Automatic email trigger (external) → Operator appears as “Pending activation” → Status updates after first login
```

### Alert investigation and escalation
```
Alerts panel → Select active alert → Review timeline + runbook link → Choose acknowledge or silence → Provide resolution notes → Alert moves to history with applied filters retained → Optional escalation creates linked incident
```

### Audit log review
```
Global navigation → Activity Log → Apply date/action filters → Inspect lifecycle entry → Follow deep link to mint detail or configuration diff → Export filtered results (CSV/JSON)
```

## Wireframes & mockups
### Low-fidelity layout guidance
- **Global shell:** Persistent top header with brandmark, environment tag, session status, and quick access to role switcher. Left navigation rail lists Dashboard, Mints, Configuration, Operators, Alerts, Activity Log, and Settings.
- **Dashboard:** Two-column responsive grid. Left column displays mint cards (state badges, revision info). Right column stacks open alerts list and recent activity feed. Global KPIs live in compact tiles above the fold.
- **Mint detail:** Tabbed view with Overview, Lifecycle, Configuration, Alerts, and Activity. Summary header includes state chip, owner team, and last change timestamp.
- **Dialogs:** Three-step wizard modal for lifecycle and configuration actions with progress indicator at top and contextual help in a right-aligned sidebar.

### High-fidelity direction
- Use a 12-column grid at ≥1280px, collapsing to 6 columns for tablets (≥768px) and a single column for narrow viewports.
- Employ card components with subtle elevation (box-shadow 0 8px 16px rgba(16, 24, 40, 0.08)) and 16px border radii to echo Cashu branding.
- Charts (alert counts, mint growth) should use lightweight sparklines or donut charts with accessible color palettes (contrast ratio ≥ 3:1 against white backgrounds).
- Provide sample Figma frames for Dashboard, Mint detail, Operator list, Alert detail, and Settings pages with annotations on spacing, typography, and component usage.

## Interaction design
- **State conventions:**
  - Buttons: default (filled brand), hover (raise + lighten 4%), pressed (darken 6% + inset shadow), focus (2px brand outline), disabled (20% opacity with preserved label contrast).
  - Inputs: neutral border in idle state, brand border + shadow on focus, error state with red border and inline helper text.
  - Tabs: active tab uses brand underline + bold label; inactive tabs display medium contrast text.
- **Feedback patterns:** Inline spinners for loading lists, skeletons for large panes, and non-blocking toasts for success/info; errors appear as sticky banners scoped to the affected module.
- **Animations & transitions:**
  - Navigation transitions cross-fade content panes over 150ms; modals scale from 96% to 100% with ease-out timing (200ms).
  - Alerts list supports slide-in/out for acknowledgment actions; configuration diff toggles animate height changes to maintain context.
  - Use motion sparingly to maintain perceived performance and respect users with reduced-motion preferences (prefer fade over slide when `prefers-reduced-motion` is enabled).
- **Keyboard interaction:** Define logical tab order, provide `Esc` to close modals, `Enter` to confirm primary actions, and `Space` to toggle switches. Focus should return to the triggering control after modals close.

## Component library / design system reference
- Build atop a React component system (e.g., Chakra UI or custom) with theming support. Define tokens for spacing (4px grid), typography, colors, elevations, and radii.
- **Buttons:** Primary, secondary, tertiary, destructive, icon-only variants. Maintain min-width 96px, 40px height, 16px horizontal padding. Loading state replaces label with spinner + accessible label.
- **Inputs:** Text, number, select, multiselect, textarea, toggle, radio group, checkbox. Include validation messaging, helper text, and optional input adornments (icons, units).
- **Tables & data lists:** Responsive tables with sticky headers, row selection, inline filters, column visibility toggles. Provide compact and comfortable density modes.
- **Cards & panels:** Use for dashboards and summary areas. Support collapsible sections with animation described above.
- **Modals & drawers:** Standard modal width 640px; wide variant 880px. Provide confirm/cancel buttons; destructive actions require explicit confirmation step.
- **Navigation:** Global sidebar with hierarchical groups; breadcrumbs inside content area; tabs for secondary navigation. Maintain keyboard shortcuts for quick switching (e.g., `g` + `d` for Dashboard).
- Document all components in Storybook or similar with usage guidelines, props, accessibility notes, and interaction examples.

## Accessibility & responsiveness
- Target WCAG 2.1 AA compliance, including 4.5:1 contrast for body text and 3:1 for large headings. Provide skip-to-content link and ARIA landmarks (`banner`, `navigation`, `main`, `complementary`).
- Support keyboard-only workflows: focus outlines must be visible, and all interactive elements must be reachable and operable via keyboard.
- Ensure screen reader compatibility by pairing icons with descriptive labels, announcing dynamic content changes (e.g., toasts) via `aria-live`, and providing table summaries.
- Breakpoints:
  - ≥1440px (wide desktop): multi-column layout with additional analytics panels.
  - 1280–1439px (desktop): default layout described in wireframes.
  - 768–1279px (tablet): collapse navigation into an overlay drawer; stack panels vertically; maintain critical actions in sticky footers.
  - <768px (mobile, read-only emphasis): provide simplified views for Dashboard, Alerts, and Activity logs; mutating actions either disabled or rerouted to desktop.
- Touch support: increase touch targets to ≥48px; provide swipe gestures for navigation drawer; ensure tables support horizontal scrolling with shadow indicators.
- Mouse support: maintain hover affordances without relying solely on them for essential information; tooltips should also be accessible via focus.

## Style guide
### Typography
- Primary typeface: **Inter** (fallback: `"Inter", "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif`).
- Heading scale:
  - Display: 40px / 48px leading / 700 weight (rare, landing hero)
  - H1: 32px / 40px / 700
  - H2: 24px / 32px / 600
  - H3: 20px / 28px / 600
  - Body large: 18px / 28px / 400
  - Body default: 16px / 24px / 400
  - Caption: 14px / 20px / 500
  - Mono: `"JetBrains Mono", "SFMono-Regular", Menlo, monospace` for code/config payloads

### Color palette
- **Brand primary:** `#1F8A70` (primary UI fills, focus outlines)
- **Brand secondary:** `#2E5077` (navigation background, accents)
- **Success:** `#2E7D32`
- **Warning:** `#F9A825`
- **Danger:** `#C62828`
- **Info:** `#1976D2`
- **Neutral scale:**
  - Background: `#F7F9FC`
  - Surface: `#FFFFFF`
  - Border/subtle text: `#D0D5DD`
  - Body text: `#1F2933`
  - Muted text: `#4B5563`
- Provide dark mode equivalents with minimum contrast requirements (e.g., background `#101828`, surface `#1D2939`, text `#F2F4F7`).

### Iconography & imagery
- Use a consistent outline icon set (e.g., Lucide or Heroicons) with 20px default size, 24px for navigation. Pair critical icons with labels to avoid ambiguity.
- Status indicators use filled circles with corresponding semantic colors and accessible tooltips explaining states.
- Illustrations (empty states, onboarding) should be lightweight, flat style with brand colors and inclusive representation.

### Branding and voice
- Display the Cashu logomark in the header and login screen; provide environment badge (e.g., “Staging”) in uppercase pill.
- Tone is professional, concise, and action-oriented. Confirmation copy should summarise consequences (“Pause mint: Operators lose issuance abilities until resumed”).
- Maintain localisation readiness by avoiding hard-coded concatenation and supporting pluralisation in copy resources.

### Documentation references
- Link Figma mockups, Storybook, and content style guides from the design system workspace.
- Version the design tokens and surfaces to align with the engineering release cadence; document changes in changelog appended to this spec.
