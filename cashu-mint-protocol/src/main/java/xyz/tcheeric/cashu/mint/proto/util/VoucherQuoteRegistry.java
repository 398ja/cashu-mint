package xyz.tcheeric.cashu.mint.proto.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * Registry for storing voucher quote face values.
 *
 * <p>When a voucher mint quote is created, the gateway is charged the fee amount
 * (e.g., 100 sats for a 1000 sat voucher at 10%), but the mint must remember the
 * original face value (1000 sats) to mint the correct amount later.
 *
 * <p>This registry maintains the mapping of quote ID to original face value.
 * Uses Caffeine cache with TTL and size limits to prevent unbounded memory growth.
 *
 * <h2>Configuration</h2>
 * <p>Default limits:
 * <ul>
 *   <li>TTL: 24 hours (same as typical quote expiry)</li>
 *   <li>Max entries: 10,000 voucher quotes</li>
 * </ul>
 *
 * <p><strong>Note:</strong> This is an in-memory implementation suitable for
 * single-instance deployments. For production multi-instance deployments, consider
 * using a distributed cache (Redis) or database storage.
 *
 * @see VoucherFeeCalculator
 * @see VoucherFeeConfig
 */
@Slf4j
public final class VoucherQuoteRegistry {

    /**
     * Default TTL for voucher quotes (24 hours).
     * Should match or exceed the quote expiry time.
     */
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    /**
     * Default maximum number of voucher quotes to track.
     */
    private static final int DEFAULT_MAX_SIZE = 10_000;

    /**
     * Cache of voucher quote face values with TTL-based eviction.
     * Entries are automatically removed after DEFAULT_TTL to prevent memory leaks
     * even if removeFaceValue() is not called.
     */
    private static final Cache<String, Long> faceValues = Caffeine.newBuilder()
            .expireAfterWrite(DEFAULT_TTL)
            .maximumSize(DEFAULT_MAX_SIZE)
            .evictionListener((key, value, cause) ->
                    log.warn("Voucher face value evicted: quoteId={}, faceValue={}, cause={}",
                            key, value, cause))
            .recordStats()
            .build();

    private VoucherQuoteRegistry() {
    }

    /**
     * Store the face value for a voucher quote.
     *
     * @param quoteId   the quote identifier
     * @param faceValue the original voucher face value in satoshis
     */
    public static void storeFaceValue(String quoteId, long faceValue) {
        faceValues.put(quoteId, faceValue);
        log.debug("Stored voucher face value for quote {}: {} sats", quoteId, faceValue);
    }

    /**
     * Retrieve the face value for a voucher quote.
     *
     * @param quoteId the quote identifier
     * @return the face value in satoshis, or null if not a voucher quote
     */
    public static Long getFaceValue(String quoteId) {
        return faceValues.getIfPresent(quoteId);
    }

    /**
     * Remove face value after minting completes.
     *
     * <p>This should be called after tokens are successfully minted to clean up
     * the registry. Even if not called, entries will be automatically evicted
     * after the TTL expires.
     *
     * @param quoteId the quote identifier
     */
    public static void removeFaceValue(String quoteId) {
        faceValues.asMap().computeIfPresent(quoteId, (key, value) -> {
            log.debug("Removed voucher face value for quote {}: {} sats", key, value);
            return null;  // Return null to remove the entry
        });
    }

    /**
     * Check if a quote is a voucher quote.
     *
     * @param quoteId the quote identifier
     * @return true if this is a voucher quote, false otherwise
     */
    public static boolean isVoucherQuote(String quoteId) {
        return faceValues.getIfPresent(quoteId) != null;
    }

    /**
     * Get the number of voucher quotes currently tracked.
     *
     * <p>This is primarily useful for monitoring and debugging.
     * Note: Returns estimated size; may not be perfectly accurate.
     *
     * @return the count of voucher quotes in the registry
     */
    public static int size() {
        faceValues.cleanUp();
        return (int) faceValues.estimatedSize();
    }

    /**
     * Clear all entries from the registry.
     *
     * <p><strong>Warning:</strong> This should only be used in tests or during
     * system maintenance. Clearing the registry while voucher quotes are pending
     * will cause minting to fail.
     */
    public static void clear() {
        faceValues.cleanUp();
        long currentSize = faceValues.estimatedSize();
        faceValues.invalidateAll();
        if (currentSize > 0) {
            log.warn("Cleared {} voucher quote entries from registry", currentSize);
        }
    }

    /**
     * Get cache hit rate for monitoring.
     *
     * @return hit rate (0.0 to 1.0)
     */
    public static double getHitRate() {
        return faceValues.stats().hitRate();
    }

    /**
     * Get eviction count for monitoring.
     *
     * @return number of entries evicted
     */
    public static long getEvictionCount() {
        return faceValues.stats().evictionCount();
    }

    /**
     * Force cleanup of expired entries.
     * Normally Caffeine cleans up lazily; call this to force immediate cleanup.
     */
    public static void cleanUp() {
        faceValues.cleanUp();
    }
}
