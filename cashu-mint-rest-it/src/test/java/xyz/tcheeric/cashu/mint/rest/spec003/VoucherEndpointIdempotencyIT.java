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
 * Spec 003 T302 / FR-009 — drives {@code POST /v1/vouchers/_test_echo}
 * with various Idempotency-Key permutations and asserts:
 *
 * <ul>
 *   <li>Missing key ⇒ {@code 400 idempotency_key_required}.</li>
 *   <li>Same key + same hash ⇒ first call hits controller, replay returns
 *       the cached response with identical body.</li>
 *   <li>Same key + different hash ⇒ {@code 409 idempotency_key_conflict}.</li>
 *   <li>Different keys ⇒ independent cache entries; each reaches the
 *       controller.</li>
 * </ul>
 */
@SpringBootTest(classes = xyz.tcheeric.cashu.mint.rest.CashuMintRestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(VoucherTestEchoConfig.class)
class VoucherEndpointIdempotencyIT extends AbstractVoucherDurableIT {

    @Value("${local.server.port}")
    int port;

    private final RestTemplate restTemplate = new RestTemplate();

    @Test
    void missingIdempotencyKey_returns400() {
        HttpHeaders headers = baseHeaders();
        // Don't set Idempotency-Key.
        ResponseEntity<String> response = post(headers, "{}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("idempotency_key_required");
    }

    @Test
    void sameKeySameHash_replaysCachedResponse() {
        String key = UUID.randomUUID().toString();
        HttpHeaders headers = baseHeaders();
        headers.add("Idempotency-Key", key);

        ResponseEntity<String> first = post(headers, "{\"v\":1}");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).contains("\"v\":1");

        ResponseEntity<String> replay = post(headers, "{\"v\":1}");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody())
                .as("replay returns the cached response verbatim")
                .isEqualTo(first.getBody());

        assertThat(voucherIdempotencyKeyJpaRepository.count())
                .as("exactly one cache row for the replayed key")
                .isEqualTo(1L);
    }

    @Test
    void sameKeyDifferentHash_returns409Conflict() {
        String key = UUID.randomUUID().toString();
        HttpHeaders headers = baseHeaders();
        headers.add("Idempotency-Key", key);

        ResponseEntity<String> first = post(headers, "{\"a\":1}");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> tamper = post(headers, "{\"a\":2}");
        assertThat(tamper.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tamper.getBody()).contains("idempotency_key_conflict");
    }

    @Test
    void differentKeys_areIndependent() {
        ResponseEntity<String> a = post(headersWithKey(), "{\"who\":\"a\"}");
        ResponseEntity<String> b = post(headersWithKey(), "{\"who\":\"b\"}");
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(a.getBody()).contains("\"a\"");
        assertThat(b.getBody()).contains("\"b\"");
        assertThat(voucherIdempotencyKeyJpaRepository.count()).isEqualTo(2L);
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private HttpHeaders baseHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("admin-it", "it-admin-password", StandardCharsets.UTF_8);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders headersWithKey() {
        HttpHeaders headers = baseHeaders();
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
