package xyz.tcheeric.cashu.mint.rest.spec003;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.cashu.mint.rest.spec003.support.AbstractVoucherDurableIT;
import xyz.tcheeric.cashu.mint.rest.spec003.support.VoucherTestEchoConfig;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 003 T301 / FR-008 — drives {@code POST /v1/vouchers/_test_echo}
 * past the per-principal rate-limit bucket and asserts:
 *
 * <ul>
 *   <li>First N requests (N = bucket capacity) succeed.</li>
 *   <li>Request N+1 returns {@code 429} with {@code Retry-After: 60} and
 *       {@code X-RateLimit-Remaining: 0} headers.</li>
 *   <li>Successful responses carry a decrementing
 *       {@code X-RateLimit-Remaining} header.</li>
 * </ul>
 *
 * <p>The IT pins the capacity to {@code 3} so the test runs in a few
 * dozen ms instead of hundreds.
 */
@SpringBootTest(classes = xyz.tcheeric.cashu.mint.rest.CashuMintRestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(VoucherTestEchoConfig.class)
@TestPropertySource(properties = {
        "cashu.mint.voucher.rate-limit-tokens-per-minute=3"
})
class VoucherEndpointRateLimitIT extends AbstractVoucherDurableIT {

    private static final int CAPACITY = 3;

    @Value("${local.server.port}")
    int port;

    private final RestTemplate restTemplate = new RestTemplate();

    @Test
    void exhaustingBucket_returns429WithRetryAfter() {
        // First CAPACITY requests succeed and decrement the remaining counter.
        for (int i = 0; i < CAPACITY; i++) {
            ResponseEntity<String> ok = post(headersWithKey(), "{\"i\":" + i + "}");
            assertThat(ok.getStatusCode())
                    .as("request %d should pass", i)
                    .isEqualTo(HttpStatus.OK);
            String remaining = ok.getHeaders().getFirst("X-RateLimit-Remaining");
            assertThat(remaining).as("X-RateLimit-Remaining on success").isNotNull();
            assertThat(Integer.parseInt(remaining))
                    .as("remaining decrements from capacity")
                    .isEqualTo(CAPACITY - 1 - i);
        }

        // Request CAPACITY+1 is over the limit.
        ResponseEntity<String> denied = post(headersWithKey(), "{\"over\":true}");
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(denied.getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(denied.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(denied.getBody()).contains("rate_limit_exceeded");
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private HttpHeaders headersWithKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("admin-it", "it-admin-password", StandardCharsets.UTF_8);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        return headers;
    }

    private ResponseEntity<String> post(HttpHeaders headers, String body) {
        try {
            return restTemplate.exchange(
                    "http://localhost:" + port + "/v1/vouchers/_test_echo",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class);
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .headers(e.getResponseHeaders())
                    .body(e.getResponseBodyAsString());
        }
    }
}
