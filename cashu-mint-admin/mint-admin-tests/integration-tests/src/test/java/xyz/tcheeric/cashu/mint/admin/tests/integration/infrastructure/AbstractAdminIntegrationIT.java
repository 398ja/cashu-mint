package xyz.tcheeric.cashu.mint.admin.tests.integration.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Testcontainers;
import xyz.tcheeric.cashu.mint.admin.rest.CashuMintAdminRestApplication;

/**
 * Shared Spring + PostgreSQL test bootstrap for all integration tests.
 */
@SpringBootTest(classes = CashuMintAdminRestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(PostgresContainerExtension.class)
public abstract class AbstractAdminIntegrationIT {

    protected static final String ADMIN_TOKEN = "integration-token";
    protected static final String MINT_ADMIN_ROLE = "MINT_ADMIN";
    protected static final String USER_ADMIN_ROLE = "USER_ADMIN";
    protected static final String ALERTS_ADMIN_ROLE = "ALERTS_ADMIN";
    protected static final String OPS_ADMIN_ROLE = "OPS_ADMIN";

    @LocalServerPort
    private int localServerPort;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected AdminApiClient adminApiClient() {
        return new AdminApiClient("http://localhost:" + localServerPort, objectMapper);
    }

    @DynamicPropertySource
    static void configureProperties(final DynamicPropertyRegistry registry) {
        PostgresContainerExtension.registerProperties(registry);
    }
}
