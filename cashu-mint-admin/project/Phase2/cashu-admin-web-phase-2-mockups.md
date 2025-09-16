# Cashu Admin Web Phase 2 Mockups Package

This reference document outlines the high-fidelity mockup directions, token definitions, and deliverable checklist for phase 2 of the Cashu admin web console. It extends the UX/UI specification and wireframes by prescribing precise visual treatments, component states, and export guidelines for the design team.

## Mockup goals
- Demonstrate the end-to-end operator journey across dashboard, mint detail, configuration drafting, operator management, alert response, and settings.
- Validate the design system tokens (color, typography, spacing, elevation) against accessibility and responsiveness requirements.
- Provide production-ready annotations that engineering can translate into Storybook stories and CSS variables.

## Visual token summary
| Token category | Name | Value | Usage notes |
| --- | --- | --- | --- |
| Color | `color.brand.primary` | `#1F8A70` | Primary buttons, links, focus ring. Contrast 4.8:1 on white. |
| Color | `color.brand.secondary` | `#2E5077` | Navigation background, tertiary highlights. |
| Color | `color.surface.default` | `#FFFFFF` | Card and modal surfaces. Shadow: `0 8px 16px rgba(16, 24, 40, 0.08)` |
| Color | `color.surface.alt` | `#F7F9FC` | Dashboard background, wizard sidebar. |
| Color | `color.border.subtle` | `#D0D5DD` | Input borders, card dividers. |
| Color | `color.text.primary` | `#1F2933` | Body copy. |
| Typography | `font.family.base` | "Inter", system sans-serif stack | Apply to all text except code snippets. |
| Typography | `font.size.h1` | 32 / 40 / 700 | Section headings (page titles). |
| Typography | `font.size.body` | 16 / 24 / 400 | Default body copy. |
| Typography | `font.size.caption` | 14 / 20 / 500 | Metadata, table headers. |
| Spacing | `space.base` | 4px | Grid baseline. Multipliers: 8, 12, 16, 24, 32. |
| Radius | `radius.card` | 16px | Cards, modals, drawers. |
| Radius | `radius.control` | 8px | Buttons, inputs. |
| Elevation | `elevation.level1` | `0 4px 12px rgba(15, 23, 42, 0.12)` | Hover states for cards. |
| Elevation | `elevation.level2` | `0 16px 32px rgba(15, 23, 42, 0.12)` | Active dialogs. |

## Component styling guidance
### Buttons
- Primary (filled): background `color.brand.primary`, text white. Hover lighten by 4%, pressed darken by 6% with inset shadow `0 0 0 1px rgba(0,0,0,0.08)`.
- Secondary (outline): transparent fill, 1px border `color.brand.primary`, text `color.brand.primary`. Hover adds surface tint `rgba(31,138,112,0.08)`.
- Destructive: background `#C62828`, focus ring `#F9A825` outer outline.
- Icon-only: 40x40 container with `radius.control`, center icon at 20px.

### Inputs
- Default: border `color.border.subtle`, 12px vertical padding, 16px horizontal.
- Focus: 1px border `color.brand.primary` + outer glow `0 0 0 4px rgba(31,138,112,0.12)`.
- Error: border `#C62828`, helper text `#C62828` in caption style.
- Disabled: background `#E4E7EC`, text `#98A2B3`, maintain 2.0:1 contrast.

### Navigation
- Sidebar width 264px desktop; collapses to 72px icon rail at ≥1024px when condensed mode toggled.
- Active nav item uses left indicator bar `color.brand.primary` width 4px and background `rgba(31,138,112,0.12)`.
- Hover states shift icon color to `color.brand.primary` and text to bold weight.

### Tables
- Header row background `color.surface.alt`, uppercase caption style.
- Zebra striping optional: even rows `rgba(46,80,119,0.04)`.
- Selected row shows 2px inset border `color.brand.primary` and action toolbar emerges at top with elevation level 2.

### Cards & panels
- Title uses H3 style, optional subtitle body default. Primary action right-aligned.
- When loading, show three skeleton lines with 8px radius and animation 1.2s shimmer.

## High-fidelity boards
### Dashboard board
- Layout: 12-column grid, 24px gutters, 48px outer margin. Header height 72px.
- KPI tiles span columns 1–12 at desktop; on tablet each tile spans 6 columns, on mobile 12 columns with 16px gutter.
- Mint cards use data visualization micro charts (sparkline with gradient `#1F8A70` → `#2E5077`).
- Alerts panel includes severity badges: Critical `#C62828`, Warning `#F9A825`, Info `#1976D2` with white text and 4px radius.
- Include annotation callouts for keyboard shortcuts (`g` + `d`, etc.) and filter persistence message.

### Mint detail board
- Hero header uses gradient background `linear-gradient(135deg, #1F8A70 0%, #2E5077 100%)` with white text and overlaid mint avatar.
- Tabs anchored below hero with sticky behavior; indicator bar 3px tall.
- Lifecycle timeline uses card stack with subtle connectors (`2px` line `#D0D5DD`) and status icons from iconography set.
- Configuration comparison uses side-by-side diff: left card `Current` surface, right card `Draft` with tinted background `rgba(46,80,119,0.06)` and diff badges showing +/– counts.

### Configuration wizard board
- Modal width 880px on desktop, 100% width on tablet. Step indicator uses horizontal progress bar with nodes labelled 1–3.
- Guidance sidebar uses gradient accent `rgba(46,80,119,0.08)` to separate from form.
- Buttons follow `Continue`, `Preview`, `Apply` sequence with descriptive sublabel below primary CTA for clarity.
- Validation errors appear inline, summary toast pinned top-right after attempt.

### Operators hub board
- Table integrates avatar chips (initials) and role badges: Admin `#2E5077`, Alerts `#F9A825`, Observer `#98A2B3`.
- Bulk action bar slides from bottom with drop shadow and includes count of selected rows.
- Invite operator modal uses two-step vertical layout: Identity form, Role assignment pill selector.

### Alerts detail board
- Timeline left rail uses accent line `color.brand.secondary` with status nodes sized 16px.
- Response panel features runbook callout card with icon `book-open` and highlight background `rgba(31,138,112,0.12)`.
- Footer actions: Primary `Acknowledge`, secondary `Silence`, tertiary `Escalate` ghost button.
- Include overlay for attached evidence (JSON/log) viewer using code font stack.

### Settings board
- Sectioned cards arranged 2-up grid on desktop, single column on tablet. Each card includes icon top-left, CTA bottom-right.
- Token rotation card features inline copy-to-clipboard with tooltip states.
- Audit export history table uses download icon button (24px) and status pill for generation state.

## Interaction & motion details
- Hover states animate over 120ms ease-out; pressed states snap instantly with 60ms release.
- Modal entrance animation: scale 0.96 → 1.0 and fade 0 → 100% over 200ms.
- Reduced motion preference disables scale animation; fallback is fade only.
- Toast notifications slide in from bottom-right with 240ms ease-in-out, auto-dismiss 6s, manual close button accessible via keyboard.

## Accessibility checklist for mockups
- Ensure color contrast meets WCAG 2.1 AA (use Stark or Figma contrast plugin during review).
- Provide focus indicators for all interactive elements in at least one mockup per flow.
- Include screen reader annotation labels for non-textual elements (charts, icon buttons).
- Prepare alternate reduced motion frames where animations are replaced by opacity transitions.

## Deliverable checklist
- [ ] Figma page `Phase 2 – Admin Console` with sections matching boards above.
- [ ] Component library page referencing token names and interactive states.
- [ ] Export PNG snapshots for Dashboard, Mint detail, Operators, Alerts detail, Settings at 1440px and 768px widths.
- [ ] Documented prototype links demonstrating primary flows (dashboard triage, mint configuration, operator onboarding).
- [ ] Changelog entry appended to UX/UI specification referencing new mockups.

## Handoff guidance
- Package exported assets in `/design/phase-2/mockups/` with naming convention `cashu-admin-{screen}-{breakpoint}.png`.
- Share token JSON with engineering for integration into theming system.
- Schedule review session with admin stakeholders to validate that visuals map to functional requirements before development kickoff.
