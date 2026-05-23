package xyz.tcheeric.cashu.mint.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import xyz.tcheeric.cashu.mint.proto.service.PaymentStatusChecker;
import xyz.tcheeric.cashu.mint.webhook.PaymentNotification;
import xyz.tcheeric.cashu.mint.webhook.PaymentWebhookController;
import xyz.tcheeric.cashu.mint.webhook.QuoteStatusUpdater;
import xyz.tcheeric.cashu.mint.webhook.WebhookSignatureValidator;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for PaymentWebhookController.
 *
 * <p>These tests verify the full integration of the webhook endpoint with
 * the QuoteStatusUpdater and MintTask.
 *
 * <p>Test Coverage:
 * <ul>
 *   <li>Webhook endpoint receives and processes payment notifications</li>
 *   <li>Quote status is correctly updated in cache</li>
 *   <li>PaymentStatusChecker returns correct status after webhook</li>
 *   <li>Preimage is correctly stored and retrievable</li>
 *   <li>Idempotency handling for duplicate webhooks</li>
 *   <li>Health endpoint reports correct statistics</li>
 * </ul>
 */
@SpringBootTest(classes = xyz.tcheeric.cashu.mint.rest.CashuMintRestApplication.class)
@AutoConfigureMockMvc
@Import(PaymentWebhookIT.WebhookTestConfig.class)
@ActiveProfiles("test")
class PaymentWebhookIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private QuoteStatusUpdater quoteStatusUpdater;

    private ObjectMapper objectMapper;

    // QuoteStatusUpdater implements PaymentStatusChecker
    private PaymentStatusChecker paymentStatusChecker;

    @TestConfiguration
    static class WebhookTestConfig {
        @Bean
        @Primary
        public QuoteStatusUpdater quoteStatusUpdater() {
            // Test configuration with reasonable TTLs
            return new QuoteStatusUpdater(
                    Duration.ofHours(1),
                    Duration.ofHours(24),
                    10_485_760L,  // 10MB max weight
                    100000,
                    null, null, null, null  // Legacy cache-only path for existing IT
            );
        }

        @Bean
        @Primary
        public WebhookSignatureValidator webhookSignatureValidator() {
            // Spec 001 FR-007 made the real validator fail closed when the
            // secret is blank. These tests target the cache/controller flow,
            // not signature validation, so stub the validator to accept every
            // delivery — equivalent to the pre-spec-001 dev-mode behaviour.
            WebhookSignatureValidator stub = org.mockito.Mockito.mock(WebhookSignatureValidator.class);
            org.mockito.Mockito.when(stub.validate(org.mockito.Mockito.any(), org.mockito.Mockito.any()))
                    .thenReturn(true);
            org.mockito.Mockito.when(stub.isEnabled()).thenReturn(false);
            return stub;
        }

        @Bean
        @Primary
        public PaymentWebhookController paymentWebhookController(
                QuoteStatusUpdater quoteStatusUpdater,
                WebhookSignatureValidator signatureValidator) {
            return new PaymentWebhookController(quoteStatusUpdater, signatureValidator);
        }
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        quoteStatusUpdater.clear();
        // QuoteStatusUpdater implements PaymentStatusChecker
        paymentStatusChecker = quoteStatusUpdater;
    }

    /**
     * Tests that the webhook endpoint correctly receives and processes a payment notification.
     */
    @Test
    void webhookEndpoint_shouldProcessPaymentNotification() throws Exception {
        // Given
        PaymentNotification notification = PaymentNotification.builder()
                .quoteId("integration-test-quote-001")
                .paymentMethod("bolt11")
                .amount(5000)
                .preimage("abc123preimage")
                .paidAt(Instant.now())
                .build();

        String json = objectMapper.writeValueAsString(notification);

        // When
        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("Payment recorded"));

        // Then
        assertTrue(quoteStatusUpdater.isPaid("integration-test-quote-001"));
        assertEquals(1, quoteStatusUpdater.getCacheSize());
    }

    /**
     * Tests that the PaymentStatusChecker returns true after webhook is processed.
     */
    @Test
    void paymentStatusChecker_shouldReturnTrueAfterWebhook() throws Exception {
        // Given
        String quoteId = "status-checker-quote-002";
        assertFalse(paymentStatusChecker.isPaid(quoteId), "Quote should not be paid initially");

        PaymentNotification notification = PaymentNotification.builder()
                .quoteId(quoteId)
                .paymentMethod("bolt11")
                .amount(3000)
                .preimage("preimage002")
                .paidAt(Instant.now())
                .build();

        String json = objectMapper.writeValueAsString(notification);

        // When
        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());

        // Then
        assertTrue(paymentStatusChecker.isPaid(quoteId), "Quote should be paid after webhook");
    }

    /**
     * Tests that the preimage is correctly stored and retrievable after webhook.
     */
    @Test
    void paymentStatusChecker_shouldReturnPreimageAfterWebhook() throws Exception {
        // Given
        String quoteId = "preimage-quote-003";
        String expectedPreimage = "my-super-secret-preimage-003";

        PaymentNotification notification = PaymentNotification.builder()
                .quoteId(quoteId)
                .paymentMethod("bolt11")
                .amount(7500)
                .preimage(expectedPreimage)
                .paidAt(Instant.now())
                .build();

        String json = objectMapper.writeValueAsString(notification);

        // When
        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());

        // Then
        Optional<String> preimage = paymentStatusChecker.getPreimage(quoteId);
        assertTrue(preimage.isPresent(), "Preimage should be present");
        assertEquals(expectedPreimage, preimage.get());
    }

    /**
     * Tests that duplicate webhooks are handled idempotently.
     */
    @Test
    void webhookEndpoint_shouldHandleDuplicatesIdempotently() throws Exception {
        // Given
        String quoteId = "duplicate-quote-004";
        PaymentNotification notification = PaymentNotification.builder()
                .quoteId(quoteId)
                .paymentMethod("bolt11")
                .amount(1000)
                .preimage("preimage004")
                .paidAt(Instant.now())
                .build();

        String json = objectMapper.writeValueAsString(notification);

        // When - first request
        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Payment recorded"));

        // When - duplicate request
        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Duplicate ignored"));

        // Then
        assertTrue(quoteStatusUpdater.isPaid(quoteId));
        assertEquals(1, quoteStatusUpdater.getCacheSize());
        assertEquals(1, quoteStatusUpdater.getProcessedCount());
    }

    /**
     * Tests that cash payment method is correctly processed.
     */
    @Test
    void webhookEndpoint_shouldHandleCashPaymentMethod() throws Exception {
        // Given
        String quoteId = "cash-quote-005";
        PaymentNotification notification = PaymentNotification.builder()
                .quoteId(quoteId)
                .paymentMethod("cash")
                .amount(10000)
                .receiptId("receipt-005")
                .paidAt(Instant.now())
                .build();

        String json = objectMapper.writeValueAsString(notification);

        // When
        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());

        // Then
        assertTrue(quoteStatusUpdater.isPaid(quoteId));

        Optional<PaymentNotification> details = quoteStatusUpdater.getPaymentDetails(quoteId);
        assertTrue(details.isPresent());
        assertEquals("cash", details.get().getPaymentMethod());
        assertEquals("receipt-005", details.get().getReceiptId());
    }

    /**
     * Tests that the health endpoint returns correct statistics.
     */
    @Test
    void healthEndpoint_shouldReturnStatistics() throws Exception {
        // Given - add some payments
        for (int i = 0; i < 3; i++) {
            PaymentNotification notification = PaymentNotification.builder()
                    .quoteId("health-quote-" + i)
                    .paymentMethod("bolt11")
                    .amount(1000 * (i + 1))
                    .preimage("preimage-health-" + i)
                    .paidAt(Instant.now())
                    .build();

            mockMvc.perform(post("/webhook/payment")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(notification)))
                    .andExpect(status().isOk());
        }

        // When/Then
        mockMvc.perform(get("/webhook/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.cachedQuotes").value(3))
                .andExpect(jsonPath("$.processedNotifications").value(3));
    }

    /**
     * Tests that marking a quote as consumed removes it from the cache.
     */
    @Test
    void markConsumed_shouldRemoveFromCache() throws Exception {
        // Given
        String quoteId = "consume-quote-006";
        PaymentNotification notification = PaymentNotification.builder()
                .quoteId(quoteId)
                .paymentMethod("bolt11")
                .amount(2500)
                .preimage("preimage006")
                .paidAt(Instant.now())
                .build();

        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(notification)))
                .andExpect(status().isOk());

        assertTrue(paymentStatusChecker.isPaid(quoteId));

        // When
        paymentStatusChecker.markConsumed(quoteId);

        // Then
        assertFalse(paymentStatusChecker.isPaid(quoteId));
        assertEquals(0, quoteStatusUpdater.getCacheSize());
    }

    /**
     * Tests the full E2E flow: webhook received -> quote marked paid -> quote consumed.
     */
    @Test
    void e2eFlow_webhookToPaidToConsumed() throws Exception {
        // Step 1: Quote initially not paid
        String quoteId = "e2e-quote-007";
        assertFalse(paymentStatusChecker.isPaid(quoteId));

        // Step 2: Webhook received
        PaymentNotification notification = PaymentNotification.builder()
                .quoteId(quoteId)
                .paymentMethod("bolt11")
                .amount(50000)
                .preimage("e2e-preimage-007")
                .paidAt(Instant.now())
                .build();

        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(notification)))
                .andExpect(status().isOk());

        // Step 3: Quote is now paid
        assertTrue(paymentStatusChecker.isPaid(quoteId));
        assertTrue(paymentStatusChecker.getPreimage(quoteId).isPresent());
        assertEquals("e2e-preimage-007", paymentStatusChecker.getPreimage(quoteId).get());

        // Step 4: Tokens minted (simulated by marking consumed)
        paymentStatusChecker.markConsumed(quoteId);

        // Step 5: Quote no longer in cache (tokens were issued)
        assertFalse(paymentStatusChecker.isPaid(quoteId));
        assertFalse(paymentStatusChecker.getPreimage(quoteId).isPresent());
    }

    /**
     * Tests that multiple quotes can be processed concurrently.
     */
    @Test
    void webhookEndpoint_shouldHandleMultipleQuotes() throws Exception {
        // Given - 10 different quotes
        for (int i = 0; i < 10; i++) {
            PaymentNotification notification = PaymentNotification.builder()
                    .quoteId("multi-quote-" + i)
                    .paymentMethod(i % 2 == 0 ? "bolt11" : "cash")
                    .amount(1000 + i * 100)
                    .preimage(i % 2 == 0 ? "preimage-" + i : null)
                    .receiptId(i % 2 == 1 ? "receipt-" + i : null)
                    .paidAt(Instant.now())
                    .build();

            mockMvc.perform(post("/webhook/payment")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(notification)))
                    .andExpect(status().isOk());
        }

        // Then
        assertEquals(10, quoteStatusUpdater.getCacheSize());
        assertEquals(10, quoteStatusUpdater.getProcessedCount());

        for (int i = 0; i < 10; i++) {
            assertTrue(quoteStatusUpdater.isPaid("multi-quote-" + i));
        }
    }

    /**
     * Tests that idempotency key header is correctly processed.
     */
    @Test
    void webhookEndpoint_shouldProcessIdempotencyKeyHeader() throws Exception {
        // Given
        PaymentNotification notification = PaymentNotification.builder()
                .quoteId("idempotency-key-quote-008")
                .paymentMethod("bolt11")
                .amount(1500)
                .preimage("preimage008")
                .paidAt(Instant.now())
                .build();

        String json = objectMapper.writeValueAsString(notification);

        // When
        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "bolt11:idempotency-key-quote-008")
                        .content(json))
                .andExpect(status().isOk());

        // Then
        assertTrue(quoteStatusUpdater.isPaid("idempotency-key-quote-008"));
    }
}
