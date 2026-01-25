package xyz.tcheeric.cashu.mint.proto.service;

import java.util.Optional;

/**
 * Interface for checking payment status from various sources.
 *
 * <p>This allows the mint to check payment status from:
 * <ul>
 *   <li>Webhook cache (for push-based notifications)</li>
 *   <li>Gateway polling (fallback)</li>
 * </ul>
 *
 * <p>Implementations should be thread-safe.
 */
public interface PaymentStatusChecker {

    /**
     * Check if a quote has been paid.
     *
     * @param quoteId the quote identifier
     * @return true if payment was received
     */
    boolean isPaid(String quoteId);

    /**
     * Get the payment preimage for a paid quote (if available).
     *
     * @param quoteId the quote identifier
     * @return Optional containing preimage if available
     */
    Optional<String> getPreimage(String quoteId);

    /**
     * Mark a quote as consumed after tokens are minted.
     * This allows the checker to clean up cached state.
     *
     * @param quoteId the quote identifier
     */
    default void markConsumed(String quoteId) {
        // Default no-op - implementations may override
    }
}
