package xyz.tcheeric.cashu.mint.webhook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.mint.proto.service.PaymentStatusChecker;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages quote payment status received via webhooks.
 *
 * <p>This service caches paid quotes so that MintTask can check payment
 * status without polling the gateway. In production, consider using
 * Redis for high-availability and persistence.
 *
 * <p>Implements {@link PaymentStatusChecker} so it can be injected into MintTask.
 */
@Slf4j
@Service
public class QuoteStatusUpdater implements PaymentStatusChecker {

    /**
     * Cache of paid quotes: quoteId -> PaymentNotification
     */
    private final Map<String, PaymentNotification> paidQuotes = new ConcurrentHashMap<>();

    /**
     * Set of processed notification idempotency keys for deduplication.
     */
    private final Set<String> processedNotifications = ConcurrentHashMap.newKeySet();

    /**
     * Mark a quote as paid.
     *
     * @param notification the payment notification
     * @return true if this was a new notification, false if duplicate
     */
    public boolean markAsPaid(PaymentNotification notification) {
        String idempotencyKey = notification.getIdempotencyKey();

        // Idempotency check
        if (!processedNotifications.add(idempotencyKey)) {
            log.debug("Duplicate notification ignored: {}", idempotencyKey);
            return false;
        }

        paidQuotes.put(notification.getQuoteId(), notification);
        log.info("Quote marked as paid via webhook: quoteId={}, method={}, amount={}",
                notification.getQuoteId(), notification.getPaymentMethod(), notification.getAmount());

        return true;
    }

    /**
     * Check if a quote is paid (received via webhook).
     *
     * @param quoteId the quote identifier
     * @return true if quote was marked as paid via webhook
     */
    public boolean isPaid(String quoteId) {
        return paidQuotes.containsKey(quoteId);
    }

    /**
     * Get payment details for a paid quote.
     *
     * @param quoteId the quote identifier
     * @return Optional containing payment details if quote is paid
     */
    public Optional<PaymentNotification> getPaymentDetails(String quoteId) {
        return Optional.ofNullable(paidQuotes.get(quoteId));
    }

    /**
     * Get the preimage for a paid quote.
     *
     * @param quoteId the quote identifier
     * @return Optional containing preimage if available
     */
    public Optional<String> getPreimage(String quoteId) {
        return getPaymentDetails(quoteId).map(PaymentNotification::getPreimage);
    }

    /**
     * Remove quote after tokens are minted (cleanup).
     * Call this after successfully minting tokens to free memory.
     *
     * @param quoteId the quote identifier
     * @return the removed payment notification, or null if not found
     */
    public PaymentNotification consumeQuote(String quoteId) {
        PaymentNotification removed = paidQuotes.remove(quoteId);
        if (removed != null) {
            log.debug("Quote consumed after minting: quoteId={}", quoteId);
        }
        return removed;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void markConsumed(String quoteId) {
        consumeQuote(quoteId);
    }

    /**
     * Get the number of cached paid quotes.
     * Useful for metrics and debugging.
     *
     * @return count of paid quotes in cache
     */
    public int getCacheSize() {
        return paidQuotes.size();
    }

    /**
     * Get the number of processed notifications.
     * Useful for metrics and debugging.
     *
     * @return count of processed notification keys
     */
    public int getProcessedCount() {
        return processedNotifications.size();
    }

    /**
     * Clear all cached data.
     * Use for testing or administrative purposes only.
     */
    public void clear() {
        paidQuotes.clear();
        processedNotifications.clear();
        log.info("Quote status cache cleared");
    }
}
