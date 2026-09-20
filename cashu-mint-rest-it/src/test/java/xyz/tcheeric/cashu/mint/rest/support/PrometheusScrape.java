package xyz.tcheeric.cashu.mint.rest.support;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

/**
 * Reads {@code /actuator/prometheus} the way the real scrape job does.
 *
 * <p>{@code ManagementSecurityConfig} put the actuator surface behind the
 * operator credential in 0.36.0 (audit H-3). Four integration-test classes
 * called it without one, so every request was a {@code 401} — and because a
 * thrown {@code HttpClientErrorException} is reported as an ERROR rather than a
 * failure, 26 tests covering the money-at-risk gauges sat green-adjacent while
 * asserting nothing at all. That is the same defect those gauges exist to
 * catch: something that looks healthy while doing nothing.
 *
 * <p>It is also the mirror of the bug fixed in 0.36.5, where the production
 * Prometheus target was down with this exact {@code 401} until it was given a
 * {@code password_file}. The scrape was repaired; the tests that would have
 * caught it recurring were not.
 *
 * <p>Centralised here so the credential is stated once. It previously appeared
 * inline in thirteen places, which is how four of them came to be wrong without
 * anyone noticing.
 */
public final class PrometheusScrape {

    /**
     * Matches {@code cashu.mint.admin.username} / {@code .password} in
     * {@code application-test.properties}. If those change, this is the single
     * place to follow them.
     */
    private static final String OPERATOR_USERNAME = "admin-it";

    private static final String OPERATOR_PASSWORD = "it-admin-password";

    private PrometheusScrape() {
    }

    /**
     * Fetches the exposition body from the management port.
     *
     * @param restTemplate caller's template, so per-class error handling applies
     * @param managementPort the resolved {@code local.management.port}
     * @return the scrape body, or {@code null} if the endpoint returned none
     */
    public static String body(RestTemplate restTemplate, int managementPort) {
        return restTemplate.exchange(
                "http://localhost:" + managementPort + "/actuator/prometheus",
                HttpMethod.GET,
                new HttpEntity<>(operatorHeaders()),
                String.class).getBody();
    }

    /** Basic-auth headers carrying the operator credential. */
    public static HttpHeaders operatorHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(OPERATOR_USERNAME, OPERATOR_PASSWORD, StandardCharsets.UTF_8);
        return headers;
    }
}
