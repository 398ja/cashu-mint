package xyz.tcheeric.cashu.mint.rest.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #346 — the actuator surface must not be readable from the public API port.
 *
 * <p>Before this, actuator sat on {@code permitAll()} on the main application port, so
 * anyone who could reach the mint could read {@code /actuator/prometheus}: issuance
 * rates, outstanding liability and melt-saga failure counts. Moving actuator to its own
 * management port is what closes that, and this IT is what keeps it closed — a stray
 * {@code management.server.port} regression silently re-exposes the whole surface.
 *
 * <p>Note what this test does <em>not</em> do: it never sets {@code management.server.port}
 * itself. Setting it here would only prove that Spring Boot honours the property — it
 * would pass just as happily against a build with no management port configured at all.
 * Instead it sets {@code CASHU_MINT_MANAGEMENT_PORT}, the placeholder that
 * {@code application.properties} feeds into {@code management.server.port}. So the
 * production wiring is what's under test: delete that line from
 * {@code application.properties} and actuator falls back onto the application port and
 * these assertions fail. A value of 0 asks for a random management port, keeping the
 * test hermetic and parallel-safe; Spring publishes the resolved value as
 * {@code local.management.port}.
 */
@SpringBootTest(
        classes = xyz.tcheeric.cashu.mint.rest.CashuMintRestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("websocket-test")
@TestPropertySource(properties = {
        // Drives application.properties' management.server.port placeholder, NOT the
        // Spring property directly — see the class javadoc.
        "CASHU_MINT_MANAGEMENT_PORT=0",
        // The websocket-test profile disables observability; this IT needs the
        // prometheus endpoint actually registered, or "404 on the app port" would
        // pass for the wrong reason.
        "cashu.observability.enabled=true",
        "management.prometheus.metrics.export.enabled=true",
        "management.endpoints.web.exposure.include=health,info,prometheus,metrics",
        "voucher.enabled=false",
        "cashu.mint.webhook.shared-secret=it-shared-secret"
})
class ActuatorManagementPortIT {

    @LocalServerPort
    private int applicationPort;

    @Value("${local.management.port}")
    private int managementPort;

    /** Never follow the app port into a redirect, and never throw on 4xx — we assert on status. */
    private final RestTemplate restTemplate = new RestTemplate();

    private ResponseEntity<String> get(int port, String path) {
        return restTemplate.getForEntity("http://localhost:" + port + path, String.class);
    }

    private HttpStatus statusOf(int port, String path) {
        try {
            return HttpStatus.valueOf(get(port, path).getStatusCode().value());
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            return HttpStatus.valueOf(e.getStatusCode().value());
        }
    }

    /** The management server must be on a different port from the public API. */
    @Test
    void managementPortIsSeparateFromTheApplicationPort() {
        assertThat(managementPort)
                .as("actuator must not share the public API port")
                .isNotEqualTo(applicationPort);
    }

    /** FR: the metrics endpoint responds on the management port. */
    @Test
    void prometheusEndpointRespondsOnTheManagementPort() {
        // Every cashu_mint_* meter is registered lazily on first use, and
        // MetricsHandlerInterceptor skips /actuator/**, so without a real request through
        // the public API first the scrape body legitimately carries no mint series and this
        // assertion would pass or fail on test ordering alone.
        get(applicationPort, "/v1/info");

        ResponseEntity<String> response = get(managementPort, "/actuator/prometheus");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .as("scrape body should carry mint metrics")
                .contains("cashu_mint_");
    }

    /** FR: the metrics endpoint is NOT reachable on the public API port. */
    @Test
    void prometheusEndpointIsNotReachableOnTheApplicationPort() {
        assertThat(statusOf(applicationPort, "/actuator/prometheus"))
                .as("metrics must not be readable from the public API port")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** The rest of the actuator surface moves with it — nothing left behind on the API port. */
    @Test
    void actuatorSurfaceIsNotReachableOnTheApplicationPort() {
        assertThat(statusOf(applicationPort, "/actuator")).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(applicationPort, "/actuator/metrics")).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(applicationPort, "/actuator/health")).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Container health checks must keep working. Both compose files in this repo probe
     * readiness, so if this breaks the deployment rolls back on a false unhealthy.
     */
    @Test
    void healthAndReadinessRemainReachableOnTheManagementPort() {
        assertThat(get(managementPort, "/actuator/health").getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> readiness = get(managementPort, "/actuator/health/readiness");
        assertThat(readiness.getStatusCode().value()).isEqualTo(200);
        assertThat(readiness.getBody()).contains("UP");
    }

    /** The public API itself stays on the application port — the move must not shift it. */
    @Test
    void publicApiRemainsOnTheApplicationPort() {
        assertThat(get(applicationPort, "/v1/info").getStatusCode().value()).isEqualTo(200);
    }
}
