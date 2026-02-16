package xyz.tcheeric.cashu.mint.admin.tests.e2e.infrastructure;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JUnit 5 extension that manages E2E test infrastructure lifecycle.
 *
 * <p>This extension starts the docker-compose infrastructure before
 * the first test runs and ensures proper cleanup. It uses the
 * ExtensionContext.Store to ensure the infrastructure is started
 * only once per test suite execution.
 *
 * <p>The extension sets system properties for dynamic service URLs,
 * allowing tests to connect to the auto-started containers.
 *
 * <p>Usage:
 * <pre>
 * {@code @ExtendWith(AdminE2EExtension.class)}
 * class MyE2ETest {
 *     // Read URLs from system properties set by this extension
 * }
 * </pre>
 *
 * <p>To use external services instead of auto-starting:
 * <pre>
 * E2E_USE_EXTERNAL=true mvn verify -Pe2e-tests
 * </pre>
 */
public class AdminE2EExtension implements BeforeAllCallback {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminE2EExtension.class);

    private static final ExtensionContext.Namespace NAMESPACE =
        ExtensionContext.Namespace.create(AdminE2EExtension.class);

    private static final String STARTED_KEY = "infrastructure_started";

    @Override
    public void beforeAll(final ExtensionContext context) {
        // Use root store to ensure infrastructure is started only once per test suite
        final ExtensionContext.Store store = context.getRoot().getStore(NAMESPACE);

        store.getOrComputeIfAbsent(
            STARTED_KEY,
            key -> {
                startInfrastructure();
                return true;
            },
            Boolean.class
        );

        LOGGER.debug("admin_e2e_extension before_all test_class={}",
            context.getTestClass().map(Class::getSimpleName).orElse("unknown"));
    }

    /**
     * Starts the E2E infrastructure and sets system properties for service URLs.
     */
    private void startInfrastructure() {
        final DockerComposeE2EStack stack = DockerComposeE2EStack.shared();

        if (DockerComposeE2EStack.useExternalServices()) {
            LOGGER.info("admin_e2e_extension mode=external skipping_auto_start");
        } else {
            LOGGER.info("admin_e2e_extension starting_infrastructure");
        }

        stack.start();

        final String adminUrl = stack.adminBaseUrl();
        final String mintUrl = stack.mintBaseUrl();

        System.setProperty("admin.e2e.admin-url", adminUrl);
        System.setProperty("admin.e2e.mint-url", mintUrl);

        LOGGER.info("admin_e2e_extension infrastructure_ready admin_url={} mint_url={}", adminUrl, mintUrl);
    }
}
