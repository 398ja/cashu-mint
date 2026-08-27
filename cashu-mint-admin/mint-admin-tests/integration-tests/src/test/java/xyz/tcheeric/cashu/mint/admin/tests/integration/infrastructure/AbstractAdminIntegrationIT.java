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
    protected static final String OPS_ADMIN_ROLE = "OPS_ADMIN";

    @LocalServerPort
    private int localServerPort;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /**
     * Credential of a fully-privileged operator, created once per class.
     *
     * <p>The bootstrap credential works only while the operator store is empty, so
     * every test that needs admin powers goes through the same handover a real
     * deployment performs: create the first operator with the bootstrap credential,
     * then use that operator's own.
     *
     * @return a credential holding every role
     */
    protected String rootCredential() {
        final String[] cached = ROOTS.get(getClass());
        if (cached == null) {
            final String rootId = java.util.UUID.randomUUID().toString();
            final var created = adminApiClient().post(
                "/admin/users",
                java.util.Map.of(
                    "userId", rootId,
                    "displayName", "Root Operator",
                    "email", "root@example.com",
                    "roles", java.util.List.of(USER_ADMIN_ROLE, MINT_ADMIN_ROLE, OPS_ADMIN_ROLE)),
                ADMIN_TOKEN,
                null);
            if (created.getStatusCode().value() != 200) {
                throw new IllegalStateException(
                    "Could not create the root operator: " + created.getStatusCode());
            }
            final String credential = created.getBody().path("credential").asText();
            ROOTS.put(getClass(), new String[] {rootId, credential});
            return credential;
        }
        return cached[1];
    }

    /** Identifier of the operator {@link #rootCredential()} belongs to. */
    protected String rootId() {
        rootCredential();
        return ROOTS.get(getClass())[0];
    }


    // Keyed by test class: the operator store is truncated once per class, so a
    // credential cached across classes would outlive the operator it belongs to.
    private static final java.util.Map<Class<?>, String[]> ROOTS = new java.util.concurrent.ConcurrentHashMap<>();

    protected AdminApiClient adminApiClient() {
        return new AdminApiClient("http://localhost:" + localServerPort, objectMapper);
    }

    @DynamicPropertySource
    static void configureProperties(final DynamicPropertyRegistry registry) {
        PostgresContainerExtension.registerProperties(registry);
    }
}
