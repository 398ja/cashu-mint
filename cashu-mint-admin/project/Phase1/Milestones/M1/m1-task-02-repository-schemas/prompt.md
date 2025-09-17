# Task: Define Repository Ports and Database Schemas

## Background
Milestone M1 requires persistence abstractions that respect Clean Architecture boundaries while preparing storage for upcoming use cases. Repositories and migrations must reflect the aggregates introduced in the domain layer.

## Goal
Establish repository interfaces, DTO mappers, and initial database migrations for mint aggregates, configuration revisions, operator accounts, audit trails, and notification policies.

## Requirements
- Specify repository interfaces for each aggregate with CRUD, projection, and search methods aligned to milestone scopes.
- Model write vs read projection schemas, ensuring transactional boundaries accommodate future outbox patterns.
- Author Flyway/Liquibase migrations creating normalized tables for mints, configuration revisions, operators, audit events, and notification policies.
- Provide data mappers that translate between persistence models and domain entities without leaking framework dependencies.
- Seed initial reference data (e.g., default roles) where required, ensuring migrations are idempotent.
- Write integration tests covering repository behaviour using H2/PostgreSQL profiles with descriptive comments above each test method.

## Definition of Done
- Repositories and migrations compile and run through `mvn -q verify` with passing integration tests.
- Persistence layer honours separation between command/write models and read projections.
- Documentation describes schema layout, migration strategy, and local developer setup instructions.
