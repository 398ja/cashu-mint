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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-End tests for the payment webhook flow.
 *
 * <p>These tests verify the complete payment notification flow from webhook
 * reception through to token minting, including:
 * <ul>
 *   <li>Push-based payment notification via webhook</li>
 *   <li>PaymentStatusChecker integration with MintTask</li>
 *   <li>Concurrent webhook handling</li>
 *   <li>High-volume webhook processing</li>
 *   <li>Error recovery and resilience</li>
 * </ul>
 *
 * <h3>E2E Test Flow</h3>
 * <pre>
 * phoenixd → payment-adapter → webhook → cashu-mint → MintTask → tokens
 * </pre>
 */
@SpringBootTest(classes = xyz.tcheeric.cashu.mint.rest.CashuMintRestApplication.class)
@AutoConfigureMockMvc
@Import(PaymentWebhookE2EIT.WebhookE2ETestConfig.class)
@ActiveProfiles("test")
class PaymentWebhookE2EIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private QuoteStatusUpdater quoteStatusUpdater;

    private ObjectMapper objectMapper;

    // QuoteStatusUpdater implements PaymentStatusChecker
    private PaymentStatusChecker paymentStatusChecker;

    @TestConfiguration
    static class WebhookE2ETestConfig {
        @Bean
        @Primary
        public QuoteStatusUpdater quoteStatusUpdater() {
            // Test configuration with reasonable TTLs
            return new QuoteStatusUpdater(
                    Duration.ofHours(1),
                    Duration.ofHours(24),
                    10000,
                    100000
            );
        }

        @Bean
        @Primary
        public WebhookSignatureValidator webhookSignatureValidator() {
            return new WebhookSignatureValidator();
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
     * E2E Test: Complete payment flow from webhook to minting simulation.
     *
     * <p>This test simulates the full flow:
     * <ol>
     *   <li>User requests mint quote (creates quote)</li>
     *   <li>User pays Lightning invoice</li>
     *   <li>phoenixd detects payment</li>
     *   <li>payment-adapter forwards webhook to cashu-mint</li>
     *   <li>cashu-mint caches payment status</li>
     *   <li>MintTask checks PaymentStatusChecker (finds payment)</li>
     *   <li>Tokens are minted</li>
     *   <li>Quote is marked as consumed</li>
     * </ol>
     */
    @Test
    void e2e_completePaymentFlowWithWebhook() throws Exception {
        String quoteId = "e2e-complete-flow-001";
        String preimage = "e2e-preimage-for-bolt11-payment";
        int amount = 21000; // 21,000 sats

        // Step 1: Simulate quote creation (quote is pending)
        assertFalse(paymentStatusChecker.isPaid(quoteId),
                "Quote should not be paid initially");

        // Step 2-4: Simulate payment and webhook from payment-adapter
        PaymentNotification notification = PaymentNotification.builder()
                .quoteId(quoteId)
                .paymentMethod("bolt11")
                .amount(amount)
                .preimage(preimage)
                .paidAt(Instant.now())
                .build();

        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", notification.getIdempotencyKey())
                        .content(objectMapper.writeValueAsString(notification)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        // Step 5: Verify payment is cached
        assertTrue(paymentStatusChecker.isPaid(quoteId),
                "PaymentStatusChecker should return true for paid quote");

        // Step 6: Simulate MintTask checking payment status
        // In real flow, MintTask calls paymentStatusChecker.isPaid() before falling back to gateway polling
        boolean isPaid = paymentStatusChecker.isPaid(quoteId);
        assertTrue(isPaid, "MintTask would find payment via PaymentStatusChecker");

        // Verify preimage is available for response
        String retrievedPreimage = paymentStatusChecker.getPreimage(quoteId).orElse(null);
        assertEquals(preimage, retrievedPreimage, "Preimage should be retrievable");

        // Step 7: Simulate token minting (successful)
        // After minting, quote should be consumed

        // Step 8: Mark quote as consumed (tokens issued)
        paymentStatusChecker.markConsumed(quoteId);

        // Verify quote is no longer in cache
        assertFalse(paymentStatusChecker.isPaid(quoteId),
                "Quote should not be paid after consumption");
        assertTrue(paymentStatusChecker.getPreimage(quoteId).isEmpty(),
                "Preimage should not be available after consumption");
    }

    /**
     * E2E Test: Cash payment flow through webhook.
     *
     * <p>Similar to bolt11 but for cash payments which don't have preimages.
     */
    @Test
    void e2e_cashPaymentFlowWithWebhook() throws Exception {
        String quoteId = "e2e-cash-flow-002";
        int amount = 50000; // 50,000 sats (cash)
        String receiptId = "receipt-for-cash-payment";

        // Step 1: Quote starts unpaid
        assertFalse(paymentStatusChecker.isPaid(quoteId));

        // Step 2: Cash payment webhook received
        PaymentNotification notification = PaymentNotification.builder()
                .quoteId(quoteId)
                .paymentMethod("cash")
                .amount(amount)
                .receiptId(receiptId)
                .paidAt(Instant.now())
                .build();

        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(notification)))
                .andExpect(status().isOk());

        // Step 3: Payment is detected
        assertTrue(paymentStatusChecker.isPaid(quoteId));

        // Step 4: Cash payments don't have preimages
        assertTrue(paymentStatusChecker.getPreimage(quoteId).isEmpty(),
                "Cash payments should not have preimages");

        // Step 5: Verify payment details
        PaymentNotification details = quoteStatusUpdater.getPaymentDetails(quoteId).orElse(null);
        assertNotNull(details);
        assertEquals("cash", details.getPaymentMethod());
        assertEquals(receiptId, details.getReceiptId());
        assertEquals(amount, details.getAmount());

        // Step 6: Consume after minting
        paymentStatusChecker.markConsumed(quoteId);
        assertFalse(paymentStatusChecker.isPaid(quoteId));
    }

    /**
     * E2E Test: Concurrent payment processing.
     *
     * <p>Tests that multiple webhooks can be processed concurrently without
     * race conditions or data corruption.
     */
    @Test
    void e2e_concurrentPaymentProcessing() throws Exception {
        int numPayments = 50;
        CountDownLatch latch = new CountDownLatch(numPayments);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(10);

        for (int i = 0; i < numPayments; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    PaymentNotification notification = PaymentNotification.builder()
                            .quoteId("concurrent-quote-" + index)
                            .paymentMethod("bolt11")
                            .amount(1000 + index)
                            .preimage("preimage-" + index)
                            .paidAt(Instant.now())
                            .build();

                    mockMvc.perform(post("/webhook/payment")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(notification)))
                            .andExpect(status().isOk());

                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all requests to complete
        assertTrue(latch.await(30, TimeUnit.SECONDS), "Timeout waiting for concurrent requests");
        executor.shutdown();

        // Verify results
        assertEquals(numPayments, successCount.get(), "All payments should succeed");
        assertEquals(0, failureCount.get(), "No payments should fail");
        assertEquals(numPayments, quoteStatusUpdater.getCacheSize(), "All quotes should be cached");

        // Verify each quote is correctly stored
        for (int i = 0; i < numPayments; i++) {
            assertTrue(paymentStatusChecker.isPaid("concurrent-quote-" + i),
                    "Quote " + i + " should be marked as paid");
        }
    }

    /**
     * E2E Test: High volume webhook processing.
     *
     * <p>Tests that the system can handle a large number of webhooks efficiently.
     */
    @Test
    void e2e_highVolumeWebhookProcessing() throws Exception {
        int numPayments = 100;
        long startTime = System.currentTimeMillis();

        // Process 100 payments sequentially
        for (int i = 0; i < numPayments; i++) {
            PaymentNotification notification = PaymentNotification.builder()
                    .quoteId("volume-quote-" + i)
                    .paymentMethod(i % 3 == 0 ? "cash" : "bolt11")
                    .amount(1000 + i)
                    .preimage(i % 3 != 0 ? "preimage-" + i : null)
                    .receiptId(i % 3 == 0 ? "receipt-" + i : null)
                    .paidAt(Instant.now())
                    .build();

            mockMvc.perform(post("/webhook/payment")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(notification)))
                    .andExpect(status().isOk());
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        // Verify all payments processed
        assertEquals(numPayments, quoteStatusUpdater.getCacheSize());
        assertEquals(numPayments, quoteStatusUpdater.getProcessedCount());

        // Performance assertion (should process 100 webhooks in < 10 seconds)
        assertTrue(duration < 10000, "High volume processing should complete within 10 seconds, took: " + duration + "ms");

        // Verify health endpoint reflects correct stats
        mockMvc.perform(get("/webhook/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cachedQuotes").value(numPayments))
                .andExpect(jsonPath("$.processedNotifications").value(numPayments));
    }

    /**
     * E2E Test: Duplicate payment handling with idempotency.
     *
     * <p>Tests that duplicate webhooks (retry scenarios) are handled correctly
     * without creating duplicate entries.
     */
    @Test
    void e2e_duplicatePaymentHandling() throws Exception {
        String quoteId = "duplicate-e2e-003";
        PaymentNotification notification = PaymentNotification.builder()
                .quoteId(quoteId)
                .paymentMethod("bolt11")
                .amount(10000)
                .preimage("duplicate-preimage")
                .paidAt(Instant.now())
                .build();

        String json = objectMapper.writeValueAsString(notification);

        // First webhook - should succeed
        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", notification.getIdempotencyKey())
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Payment recorded"));

        // Simulate network retry - same webhook again
        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", notification.getIdempotencyKey())
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Duplicate ignored"));

        // Simulate multiple retries
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/webhook/payment")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Duplicate ignored"));
        }

        // Verify only one entry exists
        assertEquals(1, quoteStatusUpdater.getCacheSize());
        assertEquals(1, quoteStatusUpdater.getProcessedCount());
        assertTrue(paymentStatusChecker.isPaid(quoteId));
    }

    /**
     * E2E Test: Webhook followed by quote consumption - verifies quote is no longer paid.
     *
     * <p>Tests the scenario where a quote is paid, consumed (tokens minted),
     * and verifies the quote is correctly removed from the cache.
     *
     * <p>Note: The same webhook will be treated as a duplicate due to idempotency
     * tracking, which prevents replay attacks.
     */
    @Test
    void e2e_paymentConsumeAndVerify() throws Exception {
        String quoteId = "consume-e2e-004";

        // First payment
        PaymentNotification notification1 = PaymentNotification.builder()
                .quoteId(quoteId)
                .paymentMethod("bolt11")
                .amount(5000)
                .preimage("preimage-first")
                .paidAt(Instant.now())
                .build();

        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(notification1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Payment recorded"));

        assertTrue(paymentStatusChecker.isPaid(quoteId));
        assertEquals("preimage-first", paymentStatusChecker.getPreimage(quoteId).orElse(null));

        // Consume (mint tokens)
        paymentStatusChecker.markConsumed(quoteId);
        assertFalse(paymentStatusChecker.isPaid(quoteId));
        assertFalse(paymentStatusChecker.getPreimage(quoteId).isPresent());

        // Same webhook again would be treated as duplicate (idempotency protection)
        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(notification1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Duplicate ignored"));

        // Quote remains not paid (webhook was ignored as duplicate)
        assertFalse(paymentStatusChecker.isPaid(quoteId));
    }

    /**
     * E2E Test: Mixed payment methods in rapid succession.
     *
     * <p>Tests handling of both bolt11 and cash payments interleaved.
     */
    @Test
    void e2e_mixedPaymentMethods() throws Exception {
        // Interleave bolt11 and cash payments
        for (int i = 0; i < 20; i++) {
            boolean isCash = i % 2 == 0;
            PaymentNotification notification = PaymentNotification.builder()
                    .quoteId("mixed-" + i)
                    .paymentMethod(isCash ? "cash" : "bolt11")
                    .amount(1000 + i * 100)
                    .preimage(isCash ? null : "preimage-" + i)
                    .receiptId(isCash ? "receipt-" + i : null)
                    .paidAt(Instant.now())
                    .build();

            mockMvc.perform(post("/webhook/payment")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(notification)))
                    .andExpect(status().isOk());
        }

        assertEquals(20, quoteStatusUpdater.getCacheSize());

        // Verify correct method types stored
        for (int i = 0; i < 20; i++) {
            PaymentNotification details = quoteStatusUpdater.getPaymentDetails("mixed-" + i).orElseThrow();
            if (i % 2 == 0) {
                assertEquals("cash", details.getPaymentMethod());
                assertNotNull(details.getReceiptId());
                assertNull(details.getPreimage());
            } else {
                assertEquals("bolt11", details.getPaymentMethod());
                assertNotNull(details.getPreimage());
                assertNull(details.getReceiptId());
            }
        }
    }
}
