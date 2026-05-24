package xyz.tcheeric.cashu.mint.rest.spec004;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuoteEntity;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.rest.spec003.support.AbstractVoucherDurableIT;
import xyz.tcheeric.cashu.mint.rest.spec003.support.VoucherTestSupport;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 004 T510 / FR-009 — admin POST /admin/voucher/forensic/customer-purchases
 * accepts a raw npub, hashes it inside the mint, returns matching rows.
 * Three scenarios: in-window match, no match, and unauthenticated access.
 */
@SpringBootTest(classes = xyz.tcheeric.cashu.mint.rest.CashuMintRestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperatorForensicLookupIT extends AbstractVoucherDurableIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PersistenceContext(unitName = "cashu-mint-jpa")
    EntityManager entityManager;

    @Value("${local.server.port}")
    int port;

    private final RestTemplate restTemplate = new RestTemplate();

    @Test
    void customerPurchases_withMatchingNpub_returnsRows() throws Exception {
        String rawNpub = "npub1forensic-" + UUID.randomUUID();

        VoucherQuoteEntity quote = VoucherTestSupport.unfundedQuote(
                "for-" + UUID.randomUUID(), 1234L);
        quote.setCustomerId(rawNpub);  // converter will hash on write
        quote.setLifecycleState(VoucherLifecycleState.ISSUED);
        voucherQuoteJpaRepository.saveAndFlush(quote);

        ResponseEntity<String> response = post("/admin/voucher/forensic/customer-purchases",
                "{\"customerNpub\":\"" + rawNpub + "\"}", "admin-it", "it-admin-password");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = MAPPER.readTree(response.getBody());
        assertThat(body.get("customer_hash").asText()).matches("^[0-9a-f]{64}$");
        assertThat(body.get("match_count").asInt()).isGreaterThanOrEqualTo(1);
        boolean ourQuoteFound = false;
        for (JsonNode row : body.get("matches")) {
            if (row.get("quote_id").asText().equals(quote.getQuoteId())) {
                ourQuoteFound = true;
                assertThat(row.get("face_value").asLong()).isEqualTo(1234L);
                assertThat(row.get("retention_state").asText()).isEqualTo("in_window");
            }
        }
        assertThat(ourQuoteFound).as("seeded quote present in forensic results").isTrue();
    }

    @Test
    void customerPurchases_withUnknownNpub_returnsEmpty() throws Exception {
        String unknown = "npub1never-" + UUID.randomUUID();
        ResponseEntity<String> response = post("/admin/voucher/forensic/customer-purchases",
                "{\"customerNpub\":\"" + unknown + "\"}", "admin-it", "it-admin-password");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = MAPPER.readTree(response.getBody());
        assertThat(body.get("match_count").asInt()).isZero();
        assertThat(body.get("matches")).isEmpty();
    }

    @Test
    void customerPurchases_unauthenticated_returns401() {
        ResponseEntity<String> response = post("/admin/voucher/forensic/customer-purchases",
                "{\"customerNpub\":\"npub1any\"}", null, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void customerPurchases_missingBodyField_returns400() throws Exception {
        ResponseEntity<String> response = post("/admin/voucher/forensic/customer-purchases",
                "{}", "admin-it", "it-admin-password");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("customerNpub_required");
    }

    private ResponseEntity<String> post(String path, String body, String user, String pass) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (user != null) {
            headers.setBasicAuth(user, pass, StandardCharsets.UTF_8);
        }
        try {
            return restTemplate.exchange(
                    "http://localhost:" + port + path,
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
