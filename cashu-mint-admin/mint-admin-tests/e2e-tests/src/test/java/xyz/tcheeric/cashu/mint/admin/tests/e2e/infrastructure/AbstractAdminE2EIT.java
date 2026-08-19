package xyz.tcheeric.cashu.mint.admin.tests.e2e.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared E2E base class for admin and mint API tests.
 *
 * <p>Infrastructure lifecycle is managed by {@link AdminE2EExtension}, which
 * ensures the docker-compose stack starts once per test suite and sets
 * system properties for service URLs.
 */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(AdminE2EExtension.class)
public abstract class AbstractAdminE2EIT {

    protected static final String ADMIN_TOKEN = "e2e-admin-token";
    protected static final String MINT_ADMIN_ROLE = "MINT_ADMIN";
    protected static final String USER_ADMIN_ROLE = "USER_ADMIN";
    protected static final String ALERTS_ADMIN_ROLE = "ALERTS_ADMIN";
    protected static final String OPS_ADMIN_ROLE = "OPS_ADMIN";

    protected static final String OPERATOR_ID = "00000000-0000-0000-0000-000000000000";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    protected AdminE2EClient adminApiClient() {
        return new AdminE2EClient(adminApiBaseUrl(), ADMIN_TOKEN, OBJECT_MAPPER);
    }

    protected MintE2EClient mintApiClient() {
        return new MintE2EClient(mintApiBaseUrl(), OBJECT_MAPPER);
    }

    protected String adminApiBaseUrl() {
        return System.getProperty("admin.e2e.admin-url", "http://localhost:7778");
    }

    protected String mintApiBaseUrl() {
        return System.getProperty("admin.e2e.mint-url", "http://localhost:7777");
    }

    protected Map<String, Object> actor(final String displayName) {
        return Map.of("id", OPERATOR_ID, "displayName", displayName);
    }
}
