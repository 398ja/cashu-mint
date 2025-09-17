# Task: Deliver CLI and REST Lifecycle Workflows

## Background
Milestone M2 requires operators to manage mint lifecycles from both CLI and REST interfaces. Adapters must provide validation, idempotency, and progress reporting while delegating to the interactor.

## Goal
Implement CLI commands and REST endpoints for create, update, pause, resume, and retire actions with consistent payloads, authentication, and error handling.

## Requirements
- Build Picocli commands (`mint create/update/pause/resume/retire`) supporting JSON/YAML payloads, dry-run previews, confirmation prompts, and progress tokens.
- Implement REST controllers with equivalent functionality, enforcing authentication, RBAC, and error mapping consistent with CLI responses.
- Provide request/response mappers ensuring schema parity across CLI and REST and aligning with OpenAPI documentation.
- Add presenters or formatters for CLI tables/JSON outputs and API responses, including audit references and correlation IDs.
- Write end-to-end or contract tests verifying CLI and REST parity, with descriptive comments above each test method.
- Update API documentation, CLI help, and OpenAPI specs to reflect final routes and options.

## Definition of Done
- Operators can execute lifecycle actions through CLI and REST with matching schemas and error semantics.
- Tests executed via `mvn -q verify` validate parity, authentication enforcement, and idempotency behaviours.
- Documentation and help text describe workflows, dry-run behaviour, and retry guidance.
