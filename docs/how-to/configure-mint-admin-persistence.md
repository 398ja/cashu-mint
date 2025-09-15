# Configure persistence for the mint admin module

This how-to shows operators how to point the admin service at a database, enable migrations, and pick the right profile for
development or production. The admin Spring configuration validates your datasource on startup and wires Flyway or Liquibase
based on the `mint.admin` property tree.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/config/AdminPersistenceConfiguration.java†L35-L120】【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/config/properties/MintAdminProperties.java†L9-L36】

## 1. Select the active profile

The module ships with two profiles:

* `postgres` (default) for production-like environments.
* `h2` for in-memory development and automated tests.

Set `SPRING_PROFILES_ACTIVE=postgres` (or `h2`) before launching, or append `--spring.profiles.active=postgres` when running the
jar. Each profile block in `application.yml` overrides the datasource URL, credentials, and migration locations appropriate for
that backend.【F:cashu-mint-admin/src/main/resources/application.yml†L7-L136】

## 2. Provide datasource credentials

Populate `spring.datasource.url`, `spring.datasource.username`, and `spring.datasource.password` for the active profile. The
admin configuration performs a startup check to ensure the URL is present so that accidental misconfiguration fails fast.【F:cashu-mint-admin/src/main/resources/application.yml†L84-L118】【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/config/AdminPersistenceConfiguration.java†L35-L57】

For PostgreSQL, review and adjust the default connection pool sizing under `spring.datasource.hikari.maximum-pool-size` and
`spring.datasource.hikari.minimum-idle` to match your deployment footprint.【F:cashu-mint-admin/src/main/resources/application.yml†L88-L90】

## 3. Tune Flyway migrations

Flyway is enabled by default for both profiles. Control its behavior through the `mint.admin.migrations.flyway` properties:

* Toggle Flyway entirely with `mint.admin.migrations.flyway.enabled`.
* Specify migration locations (classpath or filesystem) with the `locations` list.
* Decide whether to repair history tables on startup via `repair-on-migrate`.
* Allow a destructive reset by setting `clean-before-migrate=true` (Flyway still respects `flyway.cleanDisabled`).

The customizer applies these settings when it builds the Flyway instance.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/config/properties/MintAdminProperties.java†L21-L27】【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/config/AdminPersistenceConfiguration.java†L68-L97】 Update
`application.yml` to point at profile-specific locations such as `classpath:db/migration/admin/postgres` or `.../h2`.【F:cashu-mint-admin/src/main/resources/application.yml†L98-L136】

## 4. Opt into Liquibase when needed

Liquibase is disabled by default. Enable it with `mint.admin.migrations.liquibase.enabled=true` to run change logs instead of (or
in addition to) Flyway. Configure the change log location, schema, and optional contexts under the `liquibase` subsection.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/config/properties/MintAdminProperties.java†L29-L36】 When enabled, the
configuration bean wires Liquibase with the configured `DataSource`, applies schema overrides, and honors the `drop-first`
flag.【F:cashu-mint-admin/src/main/java/xyz/tcheeric/cashu/mint/admin/config/AdminPersistenceConfiguration.java†L100-L119】

## 5. Align JPA and schema tooling

The profile sections also set Hibernate’s DDL mode (`validate` for PostgreSQL, `update` for H2) and Flyway’s baseline handling.
Adjust these if your environment requires different schema management responsibilities.【F:cashu-mint-admin/src/main/resources/application.yml†L91-L129】 Remember that the admin module is packaged as a console application, so web-server-specific settings from the
base profile typically remain unused.

## 6. Verify the wiring

After adjusting properties, launch the admin module (for example, via `java -jar cashu-mint-admin/target/cashu-mint-admin-<version>.jar`) and watch the logs. Startup success indicates that the datasource validation passed and your chosen migration tool
applied without error. Use the [`mint config` tutorial](../tutorials/administer-mint-from-cli.md) to confirm that configuration
commands still function against the initialized persistence layer.
