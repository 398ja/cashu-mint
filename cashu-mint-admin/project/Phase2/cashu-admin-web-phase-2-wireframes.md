# Cashu Admin Web Phase 2 Wireframes

This explanation document captures the low-fidelity structure and responsive behavior for the core screens introduced in phase 2 of the Cashu admin web console. It complements the UX/UI specification by translating workflows into annotated layout blueprints that designers and engineers can iterate on together.

## Screen inventory
| Screen | Purpose | Primary actions |
| --- | --- | --- |
| Dashboard | Cross-mint situational awareness with alert triage | Acknowledge alert, filter mints, open incident |
| Mint detail | Lifecycle status, configuration, and event history | Pause/resume mint, draft config change, view logs |
| Configuration draft wizard | Step-by-step change authoring with validation | Continue, preview diff, submit change |
| Operators hub | Manage operator accounts and roles | Invite operator, edit roles, deactivate |
| Alerts detail | Triage ongoing alerts with timeline | Acknowledge, silence, escalate |
| Settings | Manage tokens, environments, and audit exports | Rotate API token, update environment tag, download audit |

## Wireframes
Each wireframe shows the base desktop layout first, followed by responsive breakpoints where relevant. Numbered callouts describe interaction hotspots and data groupings.

### Dashboard — desktop (≥1440px)
```text
+----------------------------------------------------------------------------------+
| 1 Header: Logo | Env Badge | Role Switcher | Session Status | Help | User Menu  |
+------------+---------------------------------------------------------------------+
|            | 2 KPI Tiles (Mint Supply, Active Alerts, Pending Actions)           |
| 3 Sidebar  +---------------------------------------------------------------------+
|  Dashboard | 4 Mint Status Grid (two-column cards)                              |
|  Mints     |  - Card: Mint Name | State Chip | Revision | Quick Actions          |
|  Config    |  - Inline badge for SLA breaches                                   |
|  Operators +---------------------------------------------------------------------+
|  Alerts    | 5 Alerts Panel (severity filter, list of alert rows with actions)   |
|  Activity  +---------------------------------------------------------------------+
|  Settings  | 6 Recent Activity Feed (timeline, deep links)                       |
+------------+---------------------------------------------------------------------+
| 7 Footer breadcrumbs & support links                                            |
+----------------------------------------------------------------------------------+
```
**Annotations**
1. Persistent global header with contextual utilities.
2. KPI tiles collapse to a scrollable row on tablet.
3. Sidebar remains fixed with active section highlight.
4. Cards support hover preview of latest lifecycle events.
5. Alerts list includes inline acknowledge and escalate buttons.
6. Activity feed provides jump links into mint detail.
7. Footer hosts breadcrumbs for nested contexts and support documentation.

### Dashboard — tablet (768–1280px)
```text
+----------------------------------------------------------+
| Header (condensed)                                       |
+----------------------------+-----------------------------+
| Nav Drawer Toggle          | KPI Carousel                |
+----------------------------+-----------------------------+
| Mint Cards (single column)                               |
+----------------------------------------------------------+
| Alerts Panel (stacked)                                    |
+----------------------------------------------------------+
| Activity Feed                                              |
+----------------------------------------------------------+
```
- Sidebar collapses into a drawer triggered from the header.
- KPI tiles switch to horizontal scroll; mint cards become single column.
- Alerts and activity sections stack vertically with sticky section headers.

### Mint detail — desktop
```text
+--------------------------------------------------------------------------------+
| Header: Mint Name | State Chip | Last Updated | Actions: Pause | Resume | Export |
+---------+------------------------------------------------------------------------
| 1 Tabs  | 2 Overview Summary                                                     |
| Overview|  - Key metrics, owner team, SLA badges                                 |
| Lifecycle|------------------------------------------------------------------------
| Config | 3 Lifecycle Timeline (vertical, timestamped steps)                      |
| Alerts |-------------------------------------------------------------------------
| Activity| 4 Configuration Snapshot cards (Current vs Draft)                      |
|         |-------------------------------------------------------------------------
|         | 5 Alerts Widget (open incidents for this mint)                         |
+---------+------------------------------------------------------------------------+
```
**Annotations**
1. Tabs align with navigation tokens defined in the component library.
2. Summary includes download link for latest audit file.
3. Timeline supports filtering by action category and exports.
4. Snapshot cards present diff badges linking to the draft wizard.
5. Alerts widget surfaces localised alerts with quick acknowledge.

### Configuration draft wizard — modal
```text
+------------------------------------------------------------+
| Step Indicator (1 of 3)                                    |
+----------------------+-------------------------------------+
| Guidance Sidebar     | Main Form                           |
| - Checklist          | - Form fields / yaml editor         |
| - Contacts           | - Inline validation messages        |
| - SLA impacts        |                                     |
+----------------------+-------------------------------------+
| Back                 | Save Draft         | Submit Change   |
+------------------------------------------------------------+
```
- Sidebar persists through steps with contextual help.
- Primary CTA label changes: Continue → Preview → Apply.
- Toast confirmation appears anchored bottom-right upon submission.

### Operators hub — desktop
```text
+----------------------------------------------------------------------------+
| Header: Operators | CTA: Invite Operator | Filters: Role, Status, Search    |
+------------+----------------------------------------------------------------+
| Sidebar    | Table Columns: Name | Email | Roles | Status | Last Active    |
|            | Row actions: Edit Roles | Reset MFA | Deactivate               |
+------------+----------------------------------------------------------------+
| Bulk action bar appears when rows selected (Assign Role, Disable)          |
+----------------------------------------------------------------------------+
```
- Invite dialog mirrors wizard modal shell with fewer steps.
- Role badges use color-coded tokens consistent with design system.

### Alerts detail — desktop
```text
+----------------------------------------------------------------------------+
| Alert Title | Severity Chip | Status Toggle (Active/Silenced)              |
+-----------------------------+----------------------------------------------+
| Timeline (left column)      | Response Panel (right column)                |
| - Detection event           | - Runbook link                               |
| - Automated actions         | - Owner + escalation dropdown                |
| - Operator comments         | - Notes textarea                             |
+-----------------------------+----------------------------------------------+
| Footer: Acknowledge | Silence | Escalate                                   |
+----------------------------------------------------------------------------+
```
- Timeline supports expandable entries for metadata and logs.
- Response panel maintains sticky action buttons for long notes.

### Settings — desktop
```text
+--------------------------------------------------------------------------+
| Header: Settings                                                         |
+----------------------+---------------------------------------------------+
| Sidebar (tokens,     | 1 Environment Management card                     |
| integrations, audit) | 2 API Token rotation card                         |
|                      | 3 Audit Export history                            |
+----------------------+---------------------------------------------------+
```
- Each card follows consistent title, description, primary action layout.
- Audit exports table provides download and regenerate controls.

## Responsive summary
| Breakpoint | Adjustments |
| --- | --- |
| ≥1440px | Two-column dashboards with auxiliary panels visible, timeline + diff side-by-side. |
| 1280–1439px | Standard desktop layout with condensed gutters (24px) and stacked tertiary panels. |
| 768–1279px | Sidebar collapses to overlay drawer; content stacks vertically; sticky action bars introduced. |
| <768px | Read-only emphasis: primary tables convert to cards; modals stretch full screen with top-close control. |

## Next steps
- Validate the wireframe blueprint with stakeholders and capture any additional modules needed before high-fidelity design.
- Use these structures as the base when constructing the component library pages in Storybook.
- Align copy and data requirements with the functional specification to ensure no gaps between UX and API contracts.
