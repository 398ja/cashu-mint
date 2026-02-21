package xyz.tcheeric.cashu.mint.admin.tests.e2e.infrastructure;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Shared Testcontainers compose stack for E2E tests.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li><b>Auto-start</b> (default): Launches the compose stack via Testcontainers
 *       with dynamic port mapping.</li>
 *   <li><b>External</b>: Set {@code E2E_USE_EXTERNAL=true} to skip container startup
 *       and connect to a pre-started stack on fixed ports.</li>
 * </ul>
 */
public final class DockerComposeE2EStack {

    private static final Logger LOGGER = LoggerFactory.getLogger(DockerComposeE2EStack.class);

    private static final String COMPOSE_FILE = "src/test/resources/compose/e2e-stack.yml";
    private static final String IMAGE_VERSION_FILE = "src/test/resources/compose/image-versions.env";
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(8);

    private static final String VAULT_SERVICE = "cashu-vault-jpa";
    private static final int VAULT_PORT = 3333;
    private static final String PAYMENT_ADAPTER_SERVICE = "payment-adapter-rest";
    private static final int PAYMENT_ADAPTER_PORT = 8080;
    private static final String MINT_SERVICE = "cashu-mint-rest";
    private static final int MINT_PORT = 7777;
    private static final String ADMIN_SERVICE = "mint-admin-rest";
    private static final int ADMIN_PORT = 7778;

    private static final String ONE_SHOT_INIT_SERVICE = "vault-db-init";
    private static final String ONE_SHOT_SEED_SERVICE = "vault-db-seed";

    private static final DockerComposeE2EStack INSTANCE = new DockerComposeE2EStack();

    private final AtomicBoolean started = new AtomicBoolean(false);
    private ComposeContainer composeContainer;

    private DockerComposeE2EStack() {}

    public static DockerComposeE2EStack shared() {
        return INSTANCE;
    }

    /**
     * Checks if external services mode is enabled via the {@code E2E_USE_EXTERNAL} env var.
     */
    public static boolean useExternalServices() {
        return "true".equalsIgnoreCase(System.getenv("E2E_USE_EXTERNAL"));
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            if (useExternalServices()) {
                LOGGER.info("admin_e2e_stack mode=external skipping_auto_start");
                return;
            }

            try {
                final Map<String, String> imageVersions = loadEnvFile(Path.of(IMAGE_VERSION_FILE));
                this.composeContainer = new ComposeContainer(Path.of(COMPOSE_FILE).toFile())
                    .withEnv(imageVersions)
                    .withExposedService(
                        VAULT_SERVICE,
                        VAULT_PORT,
                        Wait.forHttp("/actuator/health/readiness")
                            .forStatusCode(200)
                            .withStartupTimeout(STARTUP_TIMEOUT))
                    .withExposedService(
                        PAYMENT_ADAPTER_SERVICE,
                        PAYMENT_ADAPTER_PORT,
                        Wait.forHttp("/actuator/health/readiness")
                            .forStatusCode(200)
                            .withStartupTimeout(STARTUP_TIMEOUT))
                    .withExposedService(
                        MINT_SERVICE,
                        MINT_PORT,
                        Wait.forHttp("/actuator/health/readiness")
                            .forStatusCode(200)
                            .withStartupTimeout(STARTUP_TIMEOUT))
                    .withExposedService(
                        ADMIN_SERVICE,
                        ADMIN_PORT,
                        Wait.forHttp("/actuator/health/readiness")
                            .forStatusCode(200)
                            .withStartupTimeout(STARTUP_TIMEOUT))
                    .withLocalCompose(true);

                composeContainer.start();
                assertOneShotExitedSuccessfully(ONE_SHOT_INIT_SERVICE);
                assertOneShotExitedSuccessfully(ONE_SHOT_SEED_SERVICE);
                awaitMintInfoReadiness();

                LOGGER.info("admin_e2e_stack started admin_url={} mint_url={}", adminBaseUrl(), mintBaseUrl());

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    LOGGER.info("admin_e2e_stack shutdown_hook stopping_containers");
                    stop();
                }));
            } catch (final RuntimeException ex) {
                started.set(false);
                throw ex;
            }
        }
    }

    /**
     * Stops the compose stack if running.
     */
    public void stop() {
        if (composeContainer != null && started.get()) {
            try {
                composeContainer.stop();
            } catch (final Exception ex) {
                LOGGER.warn("admin_e2e_stack stop_error error={}", ex.getMessage());
            }
        }
    }

    public String adminBaseUrl() {
        if (useExternalServices()) {
            return getExternalUrl("ADMIN_URL", "http://localhost:7778");
        }
        start();
        final String host = composeContainer.getServiceHost(ADMIN_SERVICE, ADMIN_PORT);
        final Integer port = composeContainer.getServicePort(ADMIN_SERVICE, ADMIN_PORT);
        return "http://" + host + ":" + port;
    }

    public String mintBaseUrl() {
        if (useExternalServices()) {
            return getExternalUrl("MINT_URL", "http://localhost:7777");
        }
        start();
        final String host = composeContainer.getServiceHost(MINT_SERVICE, MINT_PORT);
        final Integer port = composeContainer.getServicePort(MINT_SERVICE, MINT_PORT);
        return "http://" + host + ":" + port;
    }

    private static String getExternalUrl(final String envVar, final String defaultValue) {
        final String value = System.getenv(envVar);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    private void awaitMintInfoReadiness() {
        final HttpClient httpClient = HttpClient.newHttpClient();
        final String mintInfoUrl = mintBaseUrl() + "/v1/info";
        final long deadlineNanos = System.nanoTime() + Duration.ofMinutes(2).toNanos();

        while (System.nanoTime() < deadlineNanos) {
            try {
                final HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(mintInfoUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
                final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (final Exception ignored) {
                // Retry until timeout.
            }

            try {
                Thread.sleep(1_000);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for mint info readiness", ex);
            }
        }

        throw new IllegalStateException("cashu-mint-rest /v1/info did not become ready within timeout");
    }

    private void assertOneShotExitedSuccessfully(final String serviceName) {
        final DockerClient dockerClient = DockerClientFactory.instance().client();
        final List<Container> containers = dockerClient.listContainersCmd()
            .withShowAll(true)
            .withLabelFilter(Map.of("com.docker.compose.service", serviceName))
            .exec();

        if (containers.isEmpty()) {
            throw new IllegalStateException("One-shot service container not found: " + serviceName);
        }

        final Container latestContainer = containers.stream()
            .max(Comparator.comparing(Container::getCreated))
            .orElseThrow(() -> new IllegalStateException("No container instance found for " + serviceName));

        final InspectContainerResponse inspect = dockerClient.inspectContainerCmd(latestContainer.getId()).exec();
        final Long exitCode = inspect.getState() != null ? inspect.getState().getExitCodeLong() : null;
        if (exitCode == null || exitCode.longValue() != 0L) {
            throw new IllegalStateException(
                "One-shot service failed: " + serviceName + " exitCode=" + String.valueOf(exitCode));
        }
    }

    private static Map<String, String> loadEnvFile(final Path path) {
        if (!Files.exists(path)) {
            throw new IllegalStateException("Missing image version manifest: " + path.toAbsolutePath());
        }

        try {
            final List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            final Map<String, String> values = new LinkedHashMap<>();
            for (final String rawLine : lines) {
                final String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                final int separatorIndex = line.indexOf('=');
                if (separatorIndex <= 0 || separatorIndex == line.length() - 1) {
                    throw new IllegalStateException("Invalid env line in " + path + ": " + line);
                }
                final String key = line.substring(0, separatorIndex).trim();
                final String value = line.substring(separatorIndex + 1).trim();
                values.put(key, value);
            }
            return values;
        } catch (final Exception ex) {
            throw new IllegalStateException("Failed to load image version manifest from " + path, ex);
        }
    }
}
