# Cashu Mint Admin Project Documentation

This directory hosts the product, UX, and technical planning assets that guide the evolution of the Cashu mint administrative experience. The materials are organised by delivery phase so engineers, designers, and operators can locate specifications aligned with the current roadmap.

## Directory structure
| Path | Description |
| --- | --- |
| [`Phase1/`](./Phase1) | Research, specification, and milestone tracking for the CLI-first release, including lifecycle management, configuration tooling, and operational readiness notes. |
| [`Phase1/specification.md`](./Phase1/specification.md) | Quick specification detailing the CLI objectives, functional scope, non-functional requirements, and deferred web milestones for phase 2 planning. |
| [`Phase1/technical-analysis.md`](./Phase1/technical-analysis.md) | Supporting technical discovery covering architecture, workflows, and integration constraints for the admin toolchain. |
| [`Phase1/Milestones/`](./Phase1/Milestones) | Milestone breakdowns (M1–M8) capturing deliverable sequencing and acceptance criteria for the CLI foundation. |
| [`Phase2/cashu-admin-web-phase-2-spec.md`](./Phase2/cashu-admin-web-phase-2-spec.md) | Functional scope, user roles, API mappings, and implementation roadmap for the phase 2 browser interface. |
| [`Phase2/cashu-admin-web-phase-2-ux-ui-spec.md`](./Phase2/cashu-admin-web-phase-2-ux-ui-spec.md) | UX/UI guidelines covering flows, interaction patterns, accessibility, and visual language for the web experience. |
| [`Phase2/cashu-admin-web-phase-2-wireframes.md`](./Phase2/cashu-admin-web-phase-2-wireframes.md) | Low-fidelity wireframes illustrating layout, responsive behaviors, and annotations for key operator workflows. |
| [`Phase2/cashu-admin-web-phase-2-mockups.md`](./Phase2/cashu-admin-web-phase-2-mockups.md) | High-fidelity mockup directions detailing design tokens, component styling, and deliverables for design handoff. |

## Using the documentation
- **Product & engineering alignment:** Start with the phase specifications to understand the problem space and feature commitments before diving into implementation details or design workstreams.
- **Design delivery:** Use the UX/UI specification for flow definitions, the wireframes document for structural blueprints, and the mockups package for pixel-level styling when producing assets or integrating with the design system.
- **Development handoff:** Pair the functional specification with the UX/UI document to ensure screens, validations, and feedback match the expected REST contracts and role permissions.
- **Operational readiness:** Consult phase 1 references when enhancing tooling, CLI parity, or auditing features to keep the browser interface aligned with existing guarantees.

## Contribution guidelines
1. Keep new documents grouped under the appropriate phase directory with descriptive filenames and top-level headings.
2. Link every new asset from this README table so downstream consumers can discover updates easily.
3. When design or product decisions supersede previous versions, update the relevant specification and note the change in-line or via appended changelog sections.
4. Ensure updates stay consistent with the overarching Cashu NUT specifications and the repository documentation standards outlined in the root `AGENTS.md`.

Maintaining this documentation hub ensures the CLI and web experiences evolve together, giving administrators confidence that tooling, APIs, and design surfaces remain coherent across releases.
