# Configure persistence for the mint admin module

This guide shows how to point the admin REST service at a database, run migrations, and pick the right profile for development or production.

## Default behavior

Out of the box the admin REST service uses an **H2 in-memory database** with PostgreSQL compatibility mode. This requires no setup and is suitable for development and testing. Data is lost when the service restarts.

## Switch to PostgreSQL

Override the datasource properties via environment variables:

```bash
export DATASOURCE_URL=jdbc:postgresql://localhost:5432/cashu_admin
export DATASOURCE_USERNAME=postgres
export DATASOURCE_PASSWORD=secret
export DATASOURCE_DRIVER=org.postgresql.Driver
```

Then start the service:

```bash
cd mint-admin-rest
mvn -q -DskipTests spring-boot:run
```

Or in Docker Compose, the `cashu-mint-admin-rest` service is pre-configured to use the admin PostgreSQL instance. See `docker-compose.dev.yml` for the full wiring.

## Flyway migrations

Flyway runs automatically at startup. Migrations live in `mint-admin-core/src/main/resources/db/migration/` and are numbered `V1` through `V10`:

| Migration | Tables created |
|-----------|---------------|
| V1 | `mints`, `configuration_revisions`, `operator_accounts`, `notification_policies`, `audit_events`, `admin_outbox` |
| V2 | Extended `audit_events` with revision linkage |
| V3 | Audit metadata (reason codes, ticket references, automation fields) |
| V4 | `mint_aggregate_snapshots`, `mint_lifecycle_history` |
| V5 | `mint_lifecycle_approval_states` |
| V6 | Request/correlation ID tracking on snapshots and history |
| V7 | `admin_users` |
| V8 | `admin_alerts`, `admin_alert_escalations` |
| V9 | `mint_health_snapshots` |
| V10 | `operational_controls` |

To disable Flyway (e.g., when migrations are managed externally):

```bash
export SPRING_FLYWAY_ENABLED=false
```

## Key persisted tables

| Table | Purpose |
|-------|---------|
| `mints` | Mint instances and lifecycle state |
| `configuration_revisions` | Configuration version history |
| `admin_users` | Operator accounts and roles |
| `admin_alerts` | Alert records with severity and acknowledgement |
| `admin_alert_escalations` | Escalation audit trail |
| `mint_health_snapshots` | Health status snapshots |
| `operational_controls` | Maintenance windows and key rotation records |
| `audit_events` | Unified audit log |
| `mint_lifecycle_history` | State transition history |
| `admin_outbox` | Event outbox for reliable publishing |

## Configuration reference

| Property | Default | Description |
|----------|---------|-------------|
| `spring.datasource.url` | `jdbc:h2:mem:cashu_admin;MODE=PostgreSQL;...` | JDBC connection URL |
| `spring.datasource.username` | `sa` | Database username |
| `spring.datasource.password` | _(empty)_ | Database password |
| `spring.datasource.driver-class-name` | `org.h2.Driver` | JDBC driver class |
| `spring.flyway.enabled` | `true` | Run Flyway migrations at startup |
| `spring.flyway.locations` | `classpath:db/migration` | Migration file locations |

## See also

- [Configuration reference](../reference/configuration.md) — full application properties
- [Deploy the web interface](deploy-web-production.md) — production deployment guide
