package xyz.tcheeric.cashu.mint.webhook;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PaymentNotification DTO.
 */
class PaymentNotificationTest {

    @Test
    void builder_shouldCreateNotification() {
        // When
        Instant now = Instant.now();
        PaymentNotification notification = PaymentNotification.builder()
                .quoteId("quote123")
                .paymentMethod("bolt11")
                .amount(1000)
                .preimage("preimage456")
                .paidAt(now)
                .build();

        // Then
        assertEquals("quote123", notification.getQuoteId());
        assertEquals("bolt11", notification.getPaymentMethod());
        assertEquals(1000, notification.getAmount());
        assertEquals("preimage456", notification.getPreimage());
        assertNull(notification.getReceiptId());
        assertEquals(now, notification.getPaidAt());
    }

    @Test
    void getIdempotencyKey_shouldCombineMethodAndQuoteId() {
        // Given
        PaymentNotification notification = PaymentNotification.builder()
                .quoteId("quote123")
                .paymentMethod("bolt11")
                .build();

        // When
        String key = notification.getIdempotencyKey();

        // Then
        assertEquals("bolt11:quote123", key);
    }

    @Test
    void getIdempotencyKey_shouldHandleCashMethod() {
        // Given
        PaymentNotification notification = PaymentNotification.builder()
                .quoteId("cash-quote-789")
                .paymentMethod("cash")
                .build();

        // When
        String key = notification.getIdempotencyKey();

        // Then
        assertEquals("cash:cash-quote-789", key);
    }

    @Test
    void noArgsConstructor_shouldCreateEmptyNotification() {
        // When
        PaymentNotification notification = new PaymentNotification();

        // Then
        assertNull(notification.getQuoteId());
        assertNull(notification.getPaymentMethod());
        assertNull(notification.getAmount());
    }

    @Test
    void allArgsConstructor_shouldSetAllFields() {
        // Given
        Instant now = Instant.now();

        // When (spec 008 added the `unit` field → all-args arity is now 7)
        PaymentNotification notification = new PaymentNotification(
                "q1", "bolt11", 500, "sat", "pre123", "receipt456", now);

        // Then
        assertEquals("q1", notification.getQuoteId());
        assertEquals("bolt11", notification.getPaymentMethod());
        assertEquals(500, notification.getAmount());
        assertEquals("sat", notification.getUnit());
        assertEquals("pre123", notification.getPreimage());
        assertEquals("receipt456", notification.getReceiptId());
        assertEquals(now, notification.getPaidAt());
    }

    @Test
    void setters_shouldUpdateFields() {
        // Given
        PaymentNotification notification = new PaymentNotification();

        // When
        notification.setQuoteId("updated-quote");
        notification.setPaymentMethod("cash");
        notification.setAmount(2000);

        // Then
        assertEquals("updated-quote", notification.getQuoteId());
        assertEquals("cash", notification.getPaymentMethod());
        assertEquals(2000, notification.getAmount());
    }
}
