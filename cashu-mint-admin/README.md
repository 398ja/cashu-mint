cashu-mint-admin
=================

Multi-module Java 21 project providing administrative tooling for Cashu mints.
It includes a core domain layer, a command-line interface, and a REST API.

Project Modules
- mint-admin-core: Domain model, use cases, and core services (no runtime).
- mint-admin-rest: Spring Boot REST API with OpenAPI UI and Jib image build.
- mint-admin-web: React admin UI. Vendors the `@imani/*` NAP packages under `mint-admin-web/vendor/` — see [VENDORED.md](mint-admin-web/vendor/VENDORED.md).

Prerequisites
- Java 21 (JDK)
- Maven 3.8+ (tested with 3.8.7)
- Docker (optional, for building/publishing the REST image with Jib)

Build
- Full build (skip tests): `mvn -DskipTests install`
- Run tests: `mvn test`

Run: CLI
- After a build, the shaded JAR is created with classifier `runner`:
  - `mint-admin-cli/target/mint-admin-cli-<version>-runner.jar`
- Show CLI help:
  - `java -jar mint-admin-cli/target/mint-admin-cli-<version>-runner.jar --help`

Run: REST API
- From the module via Maven:
  - `mvn -pl mint-admin-rest spring-boot:run`
- Or using the packaged JAR:
  - `java -jar mint-admin-rest/target/mint-admin-rest-<version>.jar`
- Default HTTP port: 8080
- OpenAPI UI (when running):
  - http://localhost:8080/swagger-ui.html
  - or http://localhost:8080/swagger-ui/index.html

Docker Image (REST) with Jib
- The `mint-admin-rest` module includes Jib configuration.
- Build and push to the configured registry (requires credentials):
  - `mvn -pl mint-admin-rest -DskipTests deploy`
  - Image: `docker.398ja.xyz/cashu-mint-admin-rest:<version>` (and `latest`)
- Alternatively, to build to your local Docker daemon:
  - `mvn -pl mint-admin-rest -DskipTests com.google.cloud.tools:jib-maven-plugin:3.4.6:dockerBuild`

Configuration
- Standard Spring Boot properties apply for the REST module.
- Examples:
  - Change port: `SERVER_PORT=8081`
  - Externalize config: place an `application.yml` on the classpath or supply `--spring.config.location`.

Development Notes
- Parent POM manages versions via Spring Boot BOM; modules inherit versions.
- Use Java 21 language level (`maven-compiler-plugin` configured via properties).
- CLI executable JAR is created via `maven-shade-plugin` (classifier: `runner`).

Troubleshooting
- If Maven resolves an unexpected parent or dependency version, run a clean build:
  - `mvn -U clean install`
- If IntelliJ shows out-of-date Maven metadata, reload the Maven projects.

