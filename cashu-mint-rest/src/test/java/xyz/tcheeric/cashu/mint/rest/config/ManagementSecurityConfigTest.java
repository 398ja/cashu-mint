package xyz.tcheeric.cashu.mint.rest.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The operational endpoints must not be readable by anyone who can reach the management port.
 *
 * <p>Actuator was moved to its own port in issue #346 and the isolation rested entirely on that
 * port not being published in the reference compose file. The image still {@code EXPOSE}s it, the
 * bind address is {@code 0.0.0.0} so that Kubernetes probes and sibling Prometheus containers
 * work, and the public security chain ends in {@code anyRequest().permitAll()}. Whoever could
 * reach the port could read issuance rates, outstanding liability and saga failure counts.
 *
 * <p>These tests pin the split: probes anonymous, operational data authenticated. They run
 * against the real management port rather than through {@code MockMvc}, because {@code MockMvc}
 * binds the main servlet context and never sees the management server at all, which is precisely
 * the seam the finding was about.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "management.endpoints.web.exposure.include=health,info,prometheus,metrics"
        })
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "voucher.enabled=false",
        "cashu.mint.admin.username=admin",
        "cashu.mint.admin.password=test-operator-password"
})
class ManagementSecurityConfigTest {

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + managementPort + path;
    }

    // /actuator/prometheus is not asserted here: micrometer-registry-prometheus is not on the
    // test classpath, so the endpoint is simply not registered and would 404 either way, proving
    // nothing. /actuator/metrics carries the same operational data and is registered, so it is
    // the honest probe for this control. Both are matched by the same
    // EndpointRequest.toAnyEndpoint() rule.

    @Test
    @DisplayName("metrics are rejected without credentials")
    void metricsRequireAuth() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/actuator/metrics"), String.class);

        assertThat(response.getStatusCode())
                .as("an unauthenticated caller must not read issuance and liability metrics")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a named metric is rejected without credentials")
    void namedMetricRequiresAuth() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/actuator/metrics/jvm.memory.used"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("health stays anonymous so the kubelet can probe it")
    void healthIsAnonymous() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/actuator/health"), String.class);

        assertThat(response.getStatusCode())
                .as("a probe that 401s is a pod that never goes ready")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("an operator with the admin credential can scrape")
    void operatorCanScrape() {
        ResponseEntity<String> response = restTemplate
                .withBasicAuth("admin", "test-operator-password")
                .getForEntity(url("/actuator/metrics"), String.class);

        assertThat(response.getStatusCode())
                .as("a scraper configured with basic_auth must still be able to read metrics")
                .isEqualTo(HttpStatus.OK);
    }
}
