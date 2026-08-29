package xyz.tcheeric.cashu.mint.rest.spec004;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
 * Spec 004 T410 / SC-004 / FR-005 — verify
 * {@code voucher_idempotency_key.response_body_json} does NOT cache
 * raw npubs. Drives a POST through {@link VoucherTestEchoConfig}'s
 * echo controller with a customer npub in the request body; asserts
 * the cached response (read via raw SQL) has the npub hashed.
 */
@SpringBootTest(classes = xyz.tcheeric.cashu.mint.rest.CashuMintRestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(VoucherTestEchoConfig.class)
class IdempotencyCacheScrubbedIT extends AbstractVoucherDurableIT {

    @PersistenceContext(unitName = "cashu-mint-jpa")
    EntityManager entityManager;

    @Value("${local.server.port}")
    int port;

    private final RestTemplate restTemplate = new RestTemplate();

    @Test
    void cachedResponseBodyJsonContainsNoRawNpub() {
        String rawNpub = "npub1cache-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        // POST with the raw npub embedded as customer_id in the JSON body.
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("admin-it", "it-admin-password", StandardCharsets.UTF_8);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", idempotencyKey);

        String body = "{\"customer_id\":\"" + rawNpub + "\",\"amount\":1000}";
        ResponseEntity<String> response = post(headers, body);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        // The echo controller returns the body it received back, so the
        // 2xx response itself contains the raw npub before scrubbing.
        assertThat(response.getBody()).contains(rawNpub);

        // The CACHED row, however, MUST have the npub hashed.
        @SuppressWarnings("unchecked")
        var rows = (java.util.List<String>) entityManager.createNativeQuery(
                        "SELECT response_body_json FROM voucher_idempotency_key "
                                + "WHERE idempotency_key = :k")
                .setParameter("k", idempotencyKey)
                .getResultList();
        assertThat(rows).hasSize(1);
        String cachedJson = rows.get(0);
        assertThat(cachedJson)
                .as("cached response body MUST NOT contain raw npub (FR-005)")
                .doesNotContain(rawNpub);
        // The cache should still mention the customer_id field — just hashed.
        assertThat(cachedJson).contains("\"customer_id\"");
    }

    @Test
    void replayServesScrubbedCachedResponse() {
        String rawNpub = "npub1replay-" + UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();
        String body = "{\"customer_id\":\"" + rawNpub + "\",\"v\":1}";

        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("admin-it", "it-admin-password", StandardCharsets.UTF_8);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", idempotencyKey);

        ResponseEntity<String> first = post(headers, body);
        ResponseEntity<String> replay = post(headers, body);

        // First serves the raw echo (no scrub on the wire — only on the cache write).
        assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();
        // Replay serves the cached scrubbed bytes — raw npub MUST NOT appear.
        assertThat(replay.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(replay.getBody())
                .as("replay returns the scrubbed cached response; raw npub absent")
                .doesNotContain(rawNpub);
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
