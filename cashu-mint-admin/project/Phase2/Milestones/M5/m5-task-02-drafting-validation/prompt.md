# Task: Build Configuration Drafting and Validation Experience

## Background
Operators need structured forms and raw editors to draft configuration changes with schema-aware validation and draft persistence.

## Goal
Implement configuration editor forms, JSON/YAML toggle, validation feedback, and draft storage keyed by mint/revision.

## Requirements
- Design form-driven UI for structured configuration parameters with schema validation and inline feedback.
- Provide raw JSON/YAML editing with formatting helpers, syntax highlighting, and validation errors.
- Persist drafts locally with clear controls for discard, publish, or restore actions.
- Support collaborative cues (e.g., locking warnings) if data indicates concurrent edits.
- Write tests covering draft persistence, schema validation errors, and mode switching; annotate each test method with plain-English comments.
- Document drafting workflow, validation rules, and storage behaviour.

## Definition of Done
- Configuration drafting interface supports structured and raw editing with validations and persistence, passing tests under `mvn verify`.
- Users can switch modes without data loss and receive actionable validation guidance.
- Documentation outlines drafting best practices, storage details, and collaboration notes.
