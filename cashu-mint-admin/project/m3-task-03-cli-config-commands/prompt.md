# Task: Build Configuration Governance CLI Commands

## Background
Milestone M3 mandates CLI-first ergonomics for configuration governance, including submission, preview, apply, and rollback workflows. The CLI should leverage the new `ManageConfiguration` interactor and present machine-friendly outputs for automation.

## Goal
Extend the admin CLI with configuration governance commands that cover the full lifecycle and provide validation/diff visibility.

## Requirements
- Add commands (or subcommands) such as `mint config submit`, `mint config preview`, `mint config apply`, and `mint config rollback` following existing CLI architecture and UX patterns.
- Support YAML/JSON inputs for configuration sets, accepting file paths and stdin for automation use cases.
- Render diffs, validation summaries, approval status, and audit references using presenters that consume interactor outputs.
- Provide interactive prompts where useful (e.g., confirmation before apply/rollback) and support non-interactive flags for CI/CD pipelines.
- Ensure secrets are never echoed to stdout; integrate with vault abstractions for secure handling.
- Map interactor/application errors to user-friendly CLI messages and non-zero exit codes when operations fail.
- Add unit/integration tests for the CLI layer covering submission, preview, apply, rollback, and error paths. Include descriptive comments above each test method.
- Update CLI help/documentation strings to describe the new commands and options.

## Definition of Done
- New CLI commands function end-to-end against mocked ports/interactors and expose machine-readable outputs (e.g., JSON, diff files) when requested.
- Tests validate CLI behaviours, ensuring regression safety.
- Documentation/help output is consistent and matches repository conventions.
