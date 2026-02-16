package xyz.tcheeric.cashu.mint.admin.tests.integration.infrastructure;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Boots a shared PostgreSQL container for integration tests and exposes Spring datasource properties.
 */
public class PostgresContainerExtension implements BeforeAllCallback {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("cashu_admin_it")
        .withUsername("cashu")
        .withPassword("cashu");

    @Override
    public void beforeAll(final ExtensionContext context) {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
    }

    public static void registerProperties(final DynamicPropertyRegistry registry) {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("admin.security.api-token", () -> "integration-token");
    }
}
