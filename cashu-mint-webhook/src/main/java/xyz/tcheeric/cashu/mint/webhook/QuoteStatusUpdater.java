package xyz.tcheeric.cashu.mint.webhook;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.mint.proto.service.PaymentStatusChecker;

import java.time.Duration;
import java.util.Optional;

/**
 * Manages quote payment status received via webhooks.
 *
 * <p>This service caches paid quotes so that MintTask can check payment
 * status without polling the gateway. Uses Caffeine cache with TTL-based
 * eviction to prevent unbounded memory growth.
 *
 * <p>For high-availability deployments, consider Redis for cross-instance
 * state sharing.
 *
 * <p>Implements {@link PaymentStatusChecker} so it can be injected into MintTask.
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li>{@code webhook.cache.quote-ttl} - TTL for paid quotes (default: 1h)</li>
 *   <li>{@code webhook.cache.idempotency-ttl} - TTL for idempotency keys (default: 24h)</li>
 *   <li>{@code webhook.cache.max-quote-weight} - Max memory for quotes in bytes (default: 10MB)</li>
 *   <li>{@code webhook.cache.max-idempotency-keys} - Max idempotency keys (default: 100000)</li>
 * </ul>
 *
 * <h2>Metrics</h2>
 * When Micrometer is on the classpath, the following metrics are exported:
 * <ul>
 *   <li>{@code cache.gets{cache=webhook.paid_quotes}} - Cache hits/misses</li>
 *   <li>{@code cache.evictions{cache=webhook.paid_quotes}} - Eviction count</li>
 *   <li>{@code cache.size{cache=webhook.paid_quotes}} - Current cache size</li>
 *   <li>{@code cache.gets{cache=webhook.idempotency_keys}} - Idempotency cache hits/misses</li>
 * </ul>
 */
@Slf4j
@Service
public class QuoteStatusUpdater implements PaymentStatusChecker {

    /**
     * Estimated base size of a PaymentNotification object in bytes.
     * Accounts for object header, field references, and typical string lengths.
     */
    private static final int NOTIFICATION_BASE_SIZE = 120;

    /**
     * Cache of paid quotes: quoteId -> PaymentNotification
     * Uses weight-based eviction to handle variable-size notifications.
     * TTL ensures quotes are evicted if not consumed within the timeout.
     */
    private final Cache<String, PaymentNotification> paidQuotes;

    /**
     * Cache of processed notification idempotency keys for deduplication.
     * Longer TTL than quotes to prevent reprocessing of old notifications.
     */
    private final Cache<String, Boolean> processedNotifications;

    public QuoteStatusUpdater(
            @Value("${webhook.cache.quote-ttl:1h}") Duration quoteTtl,
            @Value("${webhook.cache.idempotency-ttl:24h}") Duration idempotencyTtl,
            @Value("${webhook.cache.max-quote-weight:10485760}") long maxQuoteWeight,
            @Value("${webhook.cache.max-idempotency-keys:100000}") int maxIdempotencyKeys,
            @Autowired(required = false) MeterRegistry meterRegistry) {

        this.paidQuotes = Caffeine.newBuilder()
                .expireAfterWrite(quoteTtl)
                .maximumWeight(maxQuoteWeight)
                .weigher((String key, PaymentNotification value) -> estimateSize(key, value))
                .recordStats()
                .evictionListener((key, value, cause) ->
                        log.debug("Quote evicted from cache: key={}, cause={}", key, cause))
                .build();

        this.processedNotifications = Caffeine.newBuilder()
                .expireAfterWrite(idempotencyTtl)
                .maximumSize(maxIdempotencyKeys)
                .recordStats()
                .build();

        // Register metrics if Micrometer is available
        if (meterRegistry != null) {
            CaffeineCacheMetrics.monitor(meterRegistry, paidQuotes, "webhook.paid_quotes");
            CaffeineCacheMetrics.monitor(meterRegistry, processedNotifications, "webhook.idempotency_keys");
            log.info("Cache metrics registered with Micrometer");
        }

        log.info("QuoteStatusUpdater initialized: quoteTtl={}, idempotencyTtl={}, maxQuoteWeight={} bytes, maxIdempotencyKeys={}",
                quoteTtl, idempotencyTtl, maxQuoteWeight, maxIdempotencyKeys);
    }

    /**
     * Estimates the memory size of a cache entry in bytes.
     * Used by the weigher for memory-based eviction.
     *
     * @param key the cache key
     * @param notification the payment notification
     * @return estimated size in bytes
     */
    private int estimateSize(String key, PaymentNotification notification) {
        int size = NOTIFICATION_BASE_SIZE;
        size += key.length() * 2;  // String chars are 2 bytes
        if (notification.getQuoteId() != null) {
            size += notification.getQuoteId().length() * 2;
        }
        if (notification.getPaymentMethod() != null) {
            size += notification.getPaymentMethod().length() * 2;
        }
        if (notification.getPreimage() != null) {
            size += notification.getPreimage().length() * 2;
        }
        if (notification.getReceiptId() != null) {
            size += notification.getReceiptId().length() * 2;
        }
        return size;
    }

    /**
     * Mark a quote as paid.
     *
     * @param notification the payment notification
     * @return true if this was a new notification, false if duplicate
     */
    public boolean markAsPaid(PaymentNotification notification) {
        String idempotencyKey = notification.getIdempotencyKey();

        // Idempotency check using putIfAbsent pattern
        Boolean existing = processedNotifications.asMap().putIfAbsent(idempotencyKey, Boolean.TRUE);
        if (existing != null) {
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
    @Override
    public boolean isPaid(String quoteId) {
        return paidQuotes.getIfPresent(quoteId) != null;
    }

    /**
     * Get payment details for a paid quote.
     *
     * @param quoteId the quote identifier
     * @return Optional containing payment details if quote is paid
     */
    public Optional<PaymentNotification> getPaymentDetails(String quoteId) {
        return Optional.ofNullable(paidQuotes.getIfPresent(quoteId));
    }

    /**
     * Get the preimage for a paid quote.
     *
     * @param quoteId the quote identifier
     * @return Optional containing preimage if available
     */
    @Override
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
        PaymentNotification removed = paidQuotes.asMap().remove(quoteId);
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
        paidQuotes.cleanUp();
        return (int) paidQuotes.estimatedSize();
    }

    /**
     * Get the number of processed notifications.
     * Useful for metrics and debugging.
     *
     * @return count of processed notification keys
     */
    public int getProcessedCount() {
        processedNotifications.cleanUp();
        return (int) processedNotifications.estimatedSize();
    }

    /**
     * Clear all cached data.
     * Use for testing or administrative purposes only.
     */
    public void clear() {
        paidQuotes.invalidateAll();
        processedNotifications.invalidateAll();
        log.info("Quote status cache cleared");
    }

    /**
     * Gets cache statistics for the paid quotes cache.
     * Useful for debugging and monitoring cache efficiency.
     *
     * @return cache hit rate (0.0 to 1.0)
     */
    public double getPaidQuotesHitRate() {
        return paidQuotes.stats().hitRate();
    }

    /**
     * Gets cache statistics for the idempotency cache.
     * Useful for debugging and monitoring cache efficiency.
     *
     * @return cache hit rate (0.0 to 1.0)
     */
    public double getIdempotencyHitRate() {
        return processedNotifications.stats().hitRate();
    }

    /**
     * Gets the eviction count for the paid quotes cache.
     *
     * @return number of entries evicted
     */
    public long getPaidQuotesEvictionCount() {
        return paidQuotes.stats().evictionCount();
    }

    /**
     * Forces cleanup of expired entries from both caches.
     * Normally Caffeine cleans up lazily, but this can be called
     * to force immediate cleanup for testing or memory pressure.
     */
    public void cleanUp() {
        paidQuotes.cleanUp();
        processedNotifications.cleanUp();
    }
}
