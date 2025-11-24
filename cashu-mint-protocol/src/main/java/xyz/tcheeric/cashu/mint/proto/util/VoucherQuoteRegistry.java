package xyz.tcheeric.cashu.mint.proto.util;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for storing voucher quote face values.
 *
 * <p>When a voucher mint quote is created, the gateway is charged the fee amount
 * (e.g., 100 sats for a 1000 sat voucher at 10%), but the mint must remember the
 * original face value (1000 sats) to mint the correct amount later.
 *
 * <p>This registry maintains the mapping of quote ID to original face value.
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

    private static final Map<String, Long> faceValues = new ConcurrentHashMap<>();

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
        return faceValues.get(quoteId);
    }

    /**
     * Remove face value after minting completes.
     *
     * <p>This should be called after tokens are successfully minted to clean up
     * the registry and prevent memory leaks.
     *
     * @param quoteId the quote identifier
     */
    public static void removeFaceValue(String quoteId) {
        Long removed = faceValues.remove(quoteId);
        if (removed != null) {
            log.debug("Removed voucher face value for quote {}: {} sats", quoteId, removed);
        }
    }

    /**
     * Check if a quote is a voucher quote.
     *
     * @param quoteId the quote identifier
     * @return true if this is a voucher quote, false otherwise
     */
    public static boolean isVoucherQuote(String quoteId) {
        return faceValues.containsKey(quoteId);
    }

    /**
     * Get the number of voucher quotes currently tracked.
     *
     * <p>This is primarily useful for monitoring and debugging.
     *
     * @return the count of voucher quotes in the registry
     */
    public static int size() {
        return faceValues.size();
    }

    /**
     * Clear all entries from the registry.
     *
     * <p><strong>Warning:</strong> This should only be used in tests or during
     * system maintenance. Clearing the registry while voucher quotes are pending
     * will cause minting to fail.
     */
    public static void clear() {
        int size = faceValues.size();
        faceValues.clear();
        if (size > 0) {
            log.warn("Cleared {} voucher quote entries from registry", size);
        }
    }
}
