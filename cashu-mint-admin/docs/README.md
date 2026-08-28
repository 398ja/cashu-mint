# Cashu Mint Admin Documentation

This directory contains documentation for the admin components, organized using the [Diataxis framework](https://diataxis.fr/).

## Modules

- `mint-admin-core` — core domain model, use case ports, services, and Flyway migrations
- `mint-admin-rest` — Spring Boot REST API (port 7778)
- `mint-admin-web` — React/TypeScript web admin interface
- `mint-admin-tests` — integration and E2E test harnesses

## Tutorials

- [Run the admin REST service with Docker](tutorials/run-admin-rest-with-docker.md) — start the service against a local stack.
- [Admin User Guide](tutorials/admin-user-guide.md) — Operating a mint end to end: lifecycle, operators, operational controls, and the audit trail.

## How-to guides

- [Create a mint and put it into service](how-to/create-a-mint.md) — Create it in the admin interface, activate it, and point a mint service at the same vault.
- [Manage the mint lifecycle via the Admin REST API](how-to/manage-mint-lifecycle-api.md) — The same sequence with `curl`, for scripts and CI.
- [Back up a keyset's private keys](how-to/back-up-keyset-private-keys.md) — Find the vault path of every denomination and read the keys out of HashiCorp Vault.
- [Configure NAP admin authentication](how-to/configure-nap-admin-authentication.md) — Super Administrator npub, enrolling Operators, choosing a signer.
- [Configure persistence](how-to/configure-mint-admin-persistence.md) — Database setup, Flyway migrations, and profiles.
- [Deploy the web interface to production](how-to/deploy-web-production.md) — Single-origin deployment, health checks, and rollback.
- [Troubleshoot the web interface](how-to/troubleshoot-web-interface.md) — Common issues, performance baselines, and correlation ID tracing.

## Reference

- [REST API reference](reference/rest-api.md) — HTTP endpoints, pagination, and error codes.
- [Configuration](reference/configuration.md) — Application properties and environment variables.
- [Admin lifecycle audit schema](reference/admin-lifecycle-audit-schema.md) — the audit tables behind lifecycle changes.

## Explanations

- [ADR 0008: NAP authenticates Operators](../../docs/adr/0008-nap-authenticates-operators-the-admin-resolves-authorisation.md) — why the integration supplies an authorisation resolver rather than NAP's ACL store.
- [Architecture](explanations/architecture.md) — Clean Architecture layers, module responsibilities, and data flow.
- [Triage: what in mint-admin actually works](explanations/admin-triage.md) — which capabilities actuate, which only record, and the evidence for each.
- [Transactional outbox](explanations/transactional-outbox.md) — how background event processing works.

## Persistence coverage

Flyway migrations under `mint-admin-core/src/main/resources/db/migration-admin`
persist every admin endpoint family:

- Lifecycle and configuration history (`mints`, `configuration_revisions`, `mint_lifecycle_history`, outbox tables)
- Operator administration (`operator_accounts`, `operator_access_audit`)
- Operational controls (`operational_controls`), including a rotation's recorded outcome

The alert and health-snapshot tables were dropped by `V11` along with the features
that wrote them.

## Build

```bash
# Build all modules (skip tests)
mvn -q -DskipTests install

# Run unit + integration tests
./mvnw -q verify -pl mint-admin-tests/integration-tests -am

# Run E2E tests (from the parent cashu-mint repository)
./mvnw verify -Pe2e-tests -pl cashu-mint-admin/mint-admin-tests/e2e-tests -am

# Build and push admin REST Docker image
cd mint-admin-rest
mvn -q -DskipTests jib:build
```
