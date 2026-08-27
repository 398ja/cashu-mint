package xyz.tcheeric.cashu.mint.admin.tests.integration.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Testcontainers;
import xyz.tcheeric.cashu.mint.admin.rest.CashuMintAdminRestApplication;
import xyz.tcheeric.cashu.mint.admin.tests.nap.NapTestHandshake;

import java.util.List;
import java.util.Map;

/**
 * Shared Spring + PostgreSQL test bootstrap for all integration tests.
 */
@SpringBootTest(classes = CashuMintAdminRestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(PostgresContainerExtension.class)
public abstract class AbstractAdminIntegrationIT {

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
     * Session of the configured Super Administrator, who holds every permission.
     *
     * @return the session cookie to replay on privileged requests
     */
    protected String superAdminSession() {
        return NapTestHandshake.sessionCookie(baseUrl(), NapTestHandshake.SUPER_ADMIN_PRIVATE_KEY);
    }

    /**
     * Provisions an Operator with its own key and signs in as it.
     *
     * @param userId the account id to create
     * @param roles  the roles the account holds
     * @return the session cookie of that Operator
     */
    protected String operatorSession(final String userId, final List<String> roles) {
        final String privateKey = NapTestHandshake.randomPrivateKey();
        final ResponseEntity<JsonNode> created = adminApiClient().post(
            "/admin/users",
            Map.of(
                "userId", userId,
                "displayName", "Operator " + userId.substring(0, 8),
                "email", userId.substring(0, 8) + "@example.com",
                "roles", roles,
                "npub", NapTestHandshake.npub(privateKey)),
            superAdminSession());
        if (created.getStatusCode().value() != 200) {
            throw new IllegalStateException("Could not provision an operator: " + created.getStatusCode());
        }
        return NapTestHandshake.sessionCookie(baseUrl(), privateKey);
    }

    protected AdminApiClient adminApiClient() {
        return new AdminApiClient(baseUrl(), objectMapper);
    }

    protected String baseUrl() {
        return "http://localhost:" + localServerPort;
    }

    @DynamicPropertySource
    static void configureProperties(final DynamicPropertyRegistry registry) {
        PostgresContainerExtension.registerProperties(registry);
        // Proofs name the deployment's audience, not the random port the server answers on.
        registry.add("nap.external-base-url", () -> NapTestHandshake.EXTERNAL_BASE_URL);
        registry.add("nap.rate-limit-enabled", () -> false);
        registry.add("nap.min-auth-response-millis", () -> 0);
        registry.add("nap.response-jitter-millis", () -> 0);
        registry.add("admin.security.super-admin-npub",
            () -> NapTestHandshake.npub(NapTestHandshake.SUPER_ADMIN_PRIVATE_KEY));
    }
}
