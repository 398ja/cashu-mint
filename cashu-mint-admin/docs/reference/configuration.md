# Configuration

Default values are sourced from `mint-admin-rest/src/main/resources/application.properties`. Override any property with an environment variable (uppercase, dots to underscores) or a `-D` system property.

## Prerequisites

The admin modules require **Java 21**. The Maven Enforcer plugin rejects other JDK versions.

## Server

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `${SERVER_PORT:${CASHU_MINT_ADMIN_PORT:7778}}` | HTTP port |
| `server.address` | `0.0.0.0` | Bind address |
| `spring.threads.virtual.enabled` | `true` | Enable virtual threads |
| `server.tomcat.threads.max` | `50` | Maximum Tomcat threads |

## Database

| Property | Default | Description |
|----------|---------|-------------|
| `spring.datasource.url` | `jdbc:h2:mem:cashu_admin;MODE=PostgreSQL;...` | JDBC URL |
| `spring.datasource.username` | `sa` | Database username |
| `spring.datasource.password` | _(empty)_ | Database password |
| `spring.datasource.driver-class-name` | `org.h2.Driver` | JDBC driver |

## Flyway

| Property | Default | Description |
|----------|---------|-------------|
| `spring.flyway.enabled` | `true` | Run migrations at startup |
| `spring.flyway.locations` | `classpath:db/migration` | Migration file locations |

## Security

| Property | Default | Description |
|----------|---------|-------------|
| `admin.security.super-admin-npub` | _(none)_ | npub of the Super Administrator (`ADMIN_SUPER_ADMIN_NPUB`) |
| `nap.external-base-url` | `http://localhost:7778` | Audience handshake proofs must name (`ADMIN_EXTERNAL_BASE_URL`) |

## Actuator

| Property | Default | Description |
|----------|---------|-------------|
| `management.endpoints.web.exposure.include` | `health,info` | Exposed actuator endpoints |
| `management.endpoint.health.probes.enabled` | `true` | Enable liveness/readiness probes |

## Logging

| Property | Default | Description |
|----------|---------|-------------|
| `logging.level.root` | `INFO` | Root log level |
| `logging.level.org.springframework` | `INFO` | Spring log level |
| `logging.level.xyz.tcheeric.cashu` | `DEBUG` | Application log level |

## Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `CASHU_MINT_ADMIN_PORT` | `7778` | Admin REST API port |
| `SERVER_PORT` | `7778` | Alias for admin port |
| `ADMIN_SUPER_ADMIN_NPUB` | _(none)_ | npub of the Super Administrator |
| `ADMIN_EXTERNAL_BASE_URL` | `http://localhost:7778` | Audience handshake proofs must name |
| `DATASOURCE_URL` | _(H2 in-memory)_ | JDBC connection URL |
| `DATASOURCE_USERNAME` | `sa` | Database username |
| `DATASOURCE_PASSWORD` | _(empty)_ | Database password |
| `DATASOURCE_DRIVER` | `org.h2.Driver` | JDBC driver class |
| `LOG_LEVEL_ROOT` | `INFO` | Root log level |
| `LOG_LEVEL_SPRING` | `INFO` | Spring log level |
| `LOG_LEVEL_CASHU` | `DEBUG` | Application log level |

## See also

- [Configure persistence](../how-to/configure-mint-admin-persistence.md) — database setup guide
- [REST API reference](rest-api.md) — endpoint documentation
