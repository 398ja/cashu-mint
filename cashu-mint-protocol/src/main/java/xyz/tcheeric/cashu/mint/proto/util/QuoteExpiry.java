package xyz.tcheeric.cashu.mint.proto.util;

import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.time.Instant;

/**
 * Converts a payment gateway's quote expiry into the absolute Unix timestamp NUT-04 requires.
 *
 * <p>NUT-04 and NUT-23 define {@code expiry} as "the Unix timestamp until which the request can be
 * paid". Every gateway this mint uses returns a relative TTL in seconds instead (phoenixd 60, the
 * cash gateway its remaining seconds), and the mint passed it through. A spec-following wallet
 * then read {@code 60} as a moment in 1970 and refused the quote as expired: cashu-ts 4.x rejects
 * any quote whose {@code expiry} is positive and in the past (cashu-mint#494).
 *
 * <p>A value that is already an absolute timestamp is passed through, so a gateway that starts
 * returning one keeps working. {@code 0} means "no expiry", matching the DTO's primitive field,
 * which cannot carry the spec's {@code null}.
 */
public final class QuoteExpiry {

    /**
     * Anything at or above this is already a Unix timestamp (2001-09-09). A relative TTL of this
     * size would be thirty years, which no gateway issues.
     */
    static final long ABSOLUTE_THRESHOLD = 1_000_000_000L;

    private QuoteExpiry() {
    }

    /**
     * @param gatewayExpiry the gateway's expiry: a relative TTL in seconds, an absolute timestamp,
     *                      or null / non-positive for none
     * @param createdAt     when the quote was created; the TTL is counted from here. Null means
     *                      the quote was created just now.
     * @return the absolute Unix timestamp in seconds, or 0 when the quote does not expire
     */
    public static int absolute(Integer gatewayExpiry, Instant createdAt) {
        if (gatewayExpiry == null || gatewayExpiry <= 0) {
            return 0;
        }
        if (gatewayExpiry >= ABSOLUTE_THRESHOLD) {
            return gatewayExpiry;
        }
        Instant from = createdAt != null ? createdAt : Instant.now();
        return clampToInt(from.getEpochSecond() + gatewayExpiry);
    }

    /**
     * When the gateway says the quote was created, or null when it does not track that or the
     * lookup fails. A status check must still answer when the gateway cannot, so a failure here
     * degrades the expiry rather than the response.
     */
    public static Instant createdAt(Gateway gateway, String quoteId) {
        try {
            return gateway.getCreatedAt(quoteId);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    /** The DTO field is an {@code int}; saturate rather than wrap to a negative past. */
    private static int clampToInt(long epochSecond) {
        return epochSecond > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) epochSecond;
    }
}
