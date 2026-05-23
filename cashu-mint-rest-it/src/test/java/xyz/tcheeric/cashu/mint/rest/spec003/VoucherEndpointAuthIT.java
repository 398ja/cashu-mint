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
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.cashu.mint.rest.spec003.support.AbstractVoucherDurableIT;
import xyz.tcheeric.cashu.mint.rest.spec003.support.VoucherTestEchoConfig;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 003 T300 / FR-007 / SC-003 — drives {@code POST /v1/vouchers/**}
 * against the Testcontainers harness and asserts:
 *
 * <ul>
 *   <li>Unauthenticated requests return {@code 401}.</li>
 *   <li>Wrong-role requests return {@code 401} (no other role mapped today).</li>
 *   <li>Authenticated requests with ADMIN credentials reach the controller.</li>
 * </ul>
 *
 * <p>Uses a test-only echo controller (registered via
 * {@link VoucherTestEchoConfig}) so the filter chain runs on the
 * production path prefix without depending on the real Nostr-coupled
 * VoucherService stack.
 */
@SpringBootTest(classes = xyz.tcheeric.cashu.mint.rest.CashuMintRestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(VoucherTestEchoConfig.class)
class VoucherEndpointAuthIT extends AbstractVoucherDurableIT {

    @Value("${local.server.port}")
    int port;

    private final RestTemplate restTemplate = new RestTemplate();

    @Test
    void unauthenticatedRequest_returns401() {
        ResponseEntity<String> response = postEcho(null, null, "{\"hello\":\"world\"}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void wrongCredentials_return401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("nobody", "wrong-password", StandardCharsets.UTF_8);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = post(headers, "{\"hello\":\"world\"}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminCredentials_reachEchoController() {
        ResponseEntity<String> response = postEcho("admin-it", "it-admin-password",
                "{\"probe\":true}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"ok\":true").contains("\"probe\":true");
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private ResponseEntity<String> postEcho(String user, String pass, String body) {
        HttpHeaders headers = new HttpHeaders();
        if (user != null) {
            headers.setBasicAuth(user, pass, StandardCharsets.UTF_8);
        }
        // Idempotency filter requires the header on POST.
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return post(headers, body);
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
