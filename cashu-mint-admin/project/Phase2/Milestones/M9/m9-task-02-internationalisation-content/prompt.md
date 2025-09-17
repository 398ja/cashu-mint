# Task: Finalise Internationalisation and Content Alignment

## Background
Milestone M9 demands localisation readiness, externalised copy, and alignment between UI content and documentation terminology.

## Goal
Externalise strings, implement locale switching, and coordinate with documentation teams to ensure consistent terminology across the product.

## Requirements
- Extract remaining hard-coded strings into locale dictionaries, including date/number formatters.
- Implement locale switching UI and persistence, verifying behaviour across modules and fallbacks.
- Review terminology with documentation team, updating copy where mismatches exist and reflecting changes in docs.
- Provide localisation testing (unit/e2e) to ensure translations render and fallback correctly; annotate each test method with plain-English comments.
- Document localisation workflows, translation file structure, and contribution guidelines for translators.
- Update docs/changelog summarising localisation readiness and content changes.

## Definition of Done
- All user-facing copy externalised with locale switching functioning across modules, validated via tests in `mvn verify`.
- Terminology alignment documented with coordinated updates between UI and docs.
- Localisation workflow documentation available for future translation efforts.
