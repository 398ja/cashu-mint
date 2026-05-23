package xyz.tcheeric.cashu.mint.rest.spec001;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.cashu.mint.jpa.entity.MintQuoteEntity;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.WebhookEvent.Outcome;
import xyz.tcheeric.cashu.mint.webhook.PaymentNotification;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 001 T200 / T202 — end-to-end webhook outcome matrix against a real
 * PostgreSQL via Testcontainers. Each test seeds a {@code MintQuoteEntity}
 * row, drives {@code POST /webhook/payment}, then inspects the persisted
 * {@code WebhookEventEntity} row.
 *
 * <p>Covers FR-005 / FR-006 / FR-008: amount-mismatch, method-mismatch,
 * accepted-first-time, duplicate (replayed identical body), and tamper
 * (replayed body with different amount).
 */
class WebhookAmountBindingIT extends AbstractMintDurableIT {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @LocalServerPortValue
    @Value("${local.server.port}")
    int port;

    private final RestTemplate restTemplate = new RestTemplate();

    @BeforeEach
    void seedQuote() {
        MintQuoteEntity quote = new MintQuoteEntity();
        quote.setQuoteId("q-it");
        quote.setAmount(10L);
        quote.setUnit("sat");
        quote.setMintUrl("https://mint.it.example");
        quote.setPaymentMethod("bolt11");
        quote.setLifecycleState(LifecycleState.PENDING);
        quote.setRequestHash("0".repeat(64));
        mintQuoteJpaRepository.save(quote);
    }

    @Test
    void accepted_advancesQuoteToPaid() {
        ResponseEntity<String> response = postWebhook(notification("preimage-accept", 10, "bolt11"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Payment recorded");

        MintQuoteEntity reloaded = mintQuoteJpaRepository.findById("q-it").orElseThrow();
        assertThat(reloaded.getLifecycleState()).isEqualTo(LifecycleState.PAID);

        assertThat(webhookEventJpaRepository.findAll())
                .hasSize(1)
                .first()
                .satisfies(e -> {
                    assertThat(e.getOutcome()).isEqualTo(Outcome.accepted);
                    assertThat(e.getQuoteId()).isEqualTo("q-it");
                    assertThat(e.getProviderEventId()).isEqualTo("preimage-accept");
                });
    }

    @Test
    void amountMismatch_keepsQuotePending() {
        ResponseEntity<String> response = postWebhook(notification("preimage-amount", 9, "bolt11"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mintQuoteJpaRepository.findById("q-it").orElseThrow().getLifecycleState())
                .isEqualTo(LifecycleState.PENDING);
        assertThat(onlyEvent().getOutcome()).isEqualTo(Outcome.amount_mismatch);
    }

    @Test
    void methodMismatch_keepsQuotePending() {
        ResponseEntity<String> response = postWebhook(notification("preimage-method", 10, "cash"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mintQuoteJpaRepository.findById("q-it").orElseThrow().getLifecycleState())
                .isEqualTo(LifecycleState.PENDING);
        assertThat(onlyEvent().getOutcome()).isEqualTo(Outcome.method_mismatch);
    }

    @Test
    void duplicate_replayed_identical_returnsOkAndKeepsOneRow() {
        PaymentNotification n = notification("preimage-dup", 10, "bolt11");
        assertThat(postWebhook(n).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> second = postWebhook(n);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).contains("Duplicate");
        assertThat(webhookEventJpaRepository.findAll()).hasSize(1);
    }

    @Test
    void tamper_replayedEventIdWithDifferentAmount() {
        PaymentNotification first = notification("preimage-tamper", 10, "bolt11");
        assertThat(postWebhook(first).getStatusCode()).isEqualTo(HttpStatus.OK);
        // Same provider_event_id (preimage) but different amount on the second delivery
        PaymentNotification tampered = notification("preimage-tamper", 11, "bolt11");
        ResponseEntity<String> tamperResponse = postWebhook(tampered);
        assertThat(tamperResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tamperResponse.getBody()).contains("Tamper");
        // The PK already holds the accepted row; tamper signal didn't insert a new one.
        assertThat(webhookEventJpaRepository.findAll())
                .hasSize(1)
                .first()
                .satisfies(e -> assertThat(e.getOutcome()).isEqualTo(Outcome.accepted));
    }

    @Test
    void orphan_whenQuoteNotInDatabase() {
        // Different quote id than what we seeded.
        PaymentNotification n = PaymentNotification.builder()
                .quoteId("q-unknown")
                .paymentMethod("bolt11")
                .amount(10)
                .preimage("preimage-orphan")
                .paidAt(Instant.now())
                .build();
        ResponseEntity<String> response = postWebhook(n);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(onlyEvent().getOutcome()).isEqualTo(Outcome.orphan);
    }

    // ---------------------- helpers ----------------------

    private PaymentNotification notification(String preimage, int amount, String method) {
        return PaymentNotification.builder()
                .quoteId("q-it")
                .paymentMethod(method)
                .amount(amount)
                .preimage(preimage)
                .paidAt(Instant.now())
                .build();
    }

    private ResponseEntity<String> postWebhook(PaymentNotification notification) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            String body = MAPPER.writeValueAsString(notification);
            headers.add("X-Webhook-Signature", sign(body));
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            return restTemplate.postForEntity(
                    "http://localhost:" + port + "/webhook/payment",
                    entity,
                    String.class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .headers(e.getResponseHeaders())
                    .body(e.getResponseBodyAsString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Mirrors the HMAC-SHA256 + Base64 scheme used by WebhookSignatureValidator. */
    private static String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("it-shared-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private xyz.tcheeric.cashu.mint.jpa.entity.WebhookEventEntity onlyEvent() {
        List<xyz.tcheeric.cashu.mint.jpa.entity.WebhookEventEntity> all = webhookEventJpaRepository.findAll();
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    /** Marker so this class still compiles even though @Value handles port injection. */
    @interface LocalServerPortValue {}
}
