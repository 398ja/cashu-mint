# Cashu Mint Admin Documentation

This directory contains documentation for the admin components, organized using the [Diataxis framework](https://diataxis.fr/).

## Modules

- `mint-admin-core` — core domain model, use case ports, services, and Flyway migrations
- `mint-admin-rest` — Spring Boot REST API (port 7778)
- `mint-admin-web` — React/TypeScript web admin interface
- `mint-admin-tests` — integration and E2E test harnesses

## Tutorials

- [Admin User Guide](tutorials/admin-user-guide.md) — Complete guide to operating a Cashu mint: lifecycle, configuration, alerts, users, and maintenance.

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

## Explanations

- [ADR 0008: NAP authenticates Operators](../../docs/adr/0008-nap-authenticates-operators-the-admin-resolves-authorisation.md) — why the integration supplies an authorisation resolver rather than NAP's ACL store.
- [Architecture](explanations/architecture.md) — Clean Architecture layers, module responsibilities, and data flow.

## Persistence coverage

Flyway migrations (`V1`–`V10`) persist all admin endpoint families:

- Lifecycle and configuration history (`mints`, `configuration_revisions`, `mint_lifecycle_history`, outbox tables)
- User administration (`admin_users`)
- Alert workflows (`admin_alerts`, `admin_alert_escalations`)
- Health snapshots (`mint_health_snapshots`)
- Operational controls (`operational_controls`)

## Build

```bash
# Build all modules (skip tests)
mvn -q -DskipTests install

# Run unit + integration tests
./mvnw -q verify -pl mint-admin-tests/integration-tests -am

# Run E2E tests
set -a
source mint-admin-tests/e2e-tests/src/test/resources/compose/image-versions.env
set +a
./mvnw -q verify -pl mint-admin-tests/e2e-tests -am -De2e.skip=false

# Build and push admin REST Docker image
cd mint-admin-rest
mvn -q -DskipTests jib:build
```

## Planning documents

Detailed design documents live in the `project/` directory:

- `project/specification.md` — functional and non-functional requirements
- `project/technical-analysis.md` — Clean Architecture analysis with port contracts
- `project/M1.md`–`project/M8.md` — milestone breakdowns
