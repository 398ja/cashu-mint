package xyz.tcheeric.cashu.mint.admin.tests.e2e.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Testcontainers;
import xyz.tcheeric.cashu.mint.admin.tests.nap.NapTestHandshake;

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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Client signed in as the Super Administrator the stack configures.
     *
     * <p>The session is opened through a real NAP handshake, the only way into the
     * admin API, using the helper the integration suite signs in with.
     */
    protected AdminE2EClient adminApiClient() {
        return new AdminE2EClient(
            adminApiBaseUrl(),
            NapTestHandshake.sessionCookie(adminApiBaseUrl(), NapTestHandshake.SUPER_ADMIN_PRIVATE_KEY),
            OBJECT_MAPPER);
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
}
