package xyz.tcheeric.cashu.mint.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for QuoteStatusUpdater.
 */
class QuoteStatusUpdaterTest {

    private QuoteStatusUpdater updater;

    @BeforeEach
    void setUp() {
        // Create with short TTLs for testing - 1 hour quote TTL, 24 hour idempotency TTL
        updater = new QuoteStatusUpdater(
                Duration.ofHours(1),
                Duration.ofHours(24),
                10000,
                100000
        );
    }

    @Test
    void markAsPaid_shouldStoreNotification() {
        // Given
        PaymentNotification notification = createNotification("quote123", "bolt11", 1000, "preimage456");

        // When
        boolean result = updater.markAsPaid(notification);

        // Then
        assertTrue(result);
        assertTrue(updater.isPaid("quote123"));
        assertEquals(1, updater.getCacheSize());
    }

    @Test
    void markAsPaid_shouldBeIdempotent() {
        // Given
        PaymentNotification notification = createNotification("quote123", "bolt11", 1000, "preimage456");

        // When
        boolean first = updater.markAsPaid(notification);
        boolean second = updater.markAsPaid(notification);

        // Then
        assertTrue(first);
        assertFalse(second); // Duplicate should return false
        assertEquals(1, updater.getCacheSize());
        assertEquals(1, updater.getProcessedCount());
    }

    @Test
    void isPaid_shouldReturnFalseForUnknownQuote() {
        // When/Then
        assertFalse(updater.isPaid("unknown-quote"));
    }

    @Test
    void getPaymentDetails_shouldReturnNotification() {
        // Given
        PaymentNotification notification = createNotification("quote123", "bolt11", 1000, "preimage456");
        updater.markAsPaid(notification);

        // When
        Optional<PaymentNotification> result = updater.getPaymentDetails("quote123");

        // Then
        assertTrue(result.isPresent());
        assertEquals("quote123", result.get().getQuoteId());
        assertEquals(1000, result.get().getAmount());
    }

    @Test
    void getPaymentDetails_shouldReturnEmptyForUnknown() {
        // When
        Optional<PaymentNotification> result = updater.getPaymentDetails("unknown");

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void getPreimage_shouldReturnPreimage() {
        // Given
        PaymentNotification notification = createNotification("quote123", "bolt11", 1000, "preimage456");
        updater.markAsPaid(notification);

        // When
        Optional<String> result = updater.getPreimage("quote123");

        // Then
        assertTrue(result.isPresent());
        assertEquals("preimage456", result.get());
    }

    @Test
    void consumeQuote_shouldRemoveQuote() {
        // Given
        PaymentNotification notification = createNotification("quote123", "bolt11", 1000, "preimage456");
        updater.markAsPaid(notification);
        assertTrue(updater.isPaid("quote123"));

        // When
        PaymentNotification removed = updater.consumeQuote("quote123");

        // Then
        assertNotNull(removed);
        assertEquals("quote123", removed.getQuoteId());
        assertFalse(updater.isPaid("quote123"));
        assertEquals(0, updater.getCacheSize());
    }

    @Test
    void consumeQuote_shouldReturnNullForUnknown() {
        // When
        PaymentNotification result = updater.consumeQuote("unknown");

        // Then
        assertNull(result);
    }

    @Test
    void markConsumed_shouldCallConsumeQuote() {
        // Given
        PaymentNotification notification = createNotification("quote123", "bolt11", 1000, "preimage456");
        updater.markAsPaid(notification);

        // When
        updater.markConsumed("quote123");

        // Then
        assertFalse(updater.isPaid("quote123"));
    }

    @Test
    void clear_shouldRemoveAllData() {
        // Given
        updater.markAsPaid(createNotification("quote1", "bolt11", 100, "p1"));
        updater.markAsPaid(createNotification("quote2", "bolt11", 200, "p2"));
        assertEquals(2, updater.getCacheSize());

        // When
        updater.clear();

        // Then
        assertEquals(0, updater.getCacheSize());
        assertEquals(0, updater.getProcessedCount());
        assertFalse(updater.isPaid("quote1"));
        assertFalse(updater.isPaid("quote2"));
    }

    @Test
    void shouldHandleMultipleQuotes() {
        // Given/When
        updater.markAsPaid(createNotification("quote1", "bolt11", 100, "p1"));
        updater.markAsPaid(createNotification("quote2", "cash", 200, null));
        updater.markAsPaid(createNotification("quote3", "bolt11", 300, "p3"));

        // Then
        assertEquals(3, updater.getCacheSize());
        assertTrue(updater.isPaid("quote1"));
        assertTrue(updater.isPaid("quote2"));
        assertTrue(updater.isPaid("quote3"));
    }

    private PaymentNotification createNotification(String quoteId, String method, int amount, String preimage) {
        return PaymentNotification.builder()
                .quoteId(quoteId)
                .paymentMethod(method)
                .amount(amount)
                .preimage(preimage)
                .paidAt(Instant.now())
                .build();
    }
}
