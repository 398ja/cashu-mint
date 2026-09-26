package xyz.tcheeric.cashu.mint.proto.util;

import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.time.Instant;
import java.util.Optional;

/**
 * Converts a payment gateway's quote expiry into the absolute Unix timestamp NUT-04 requires.
 *
 * <p>NUT-04 and NUT-23 define {@code expiry} as "the Unix timestamp until which the request can be
 * paid". Every gateway this mint uses returns a relative figure instead, and the mint passed it
 * through. A spec-following wallet then read {@code 60} as a moment in 1970 and refused the quote
 * as expired: cashu-ts 4.x rejects any quote whose {@code expiry} is positive and in the past
 * (cashu-mint#494).
 *
 * <p>The gateways do not agree on what the relative figure means, which is the whole difficulty:
 * <ul>
 *   <li>Phoenixd returns a fixed TTL and tracks when the quote was created, so the deadline is
 *       creation plus the TTL.</li>
 *   <li>The cash and Stripe gateways return the <em>seconds remaining</em>, clamped at zero, and
 *       track no creation time, so the deadline is now plus the figure.</li>
 * </ul>
 * The creation time is what tells the two apart: it is present exactly when the figure is a TTL.
 *
 * <p>A zero figure therefore means "already expired", not "never expires": a remaining-seconds
 * gateway reports 0 once its quote has lapsed. It used to come out as {@code 0}, which clients
 * read as no expiry at all, so an expired cash quote was offered as payable forever
 * (cashu-mint#503). Only a gateway that reports no figure at all ({@code null}) has a quote without
 * a deadline, reported as {@link #NO_EXPIRY} because the DTO's primitive field cannot carry the
 * spec's {@code null}.
 *
 * <p>A figure that is already an absolute timestamp is passed through, so a gateway that starts
 * returning one keeps working.
 */
@Slf4j
public final class QuoteExpiry {

    /** Reported for a quote with no payment deadline. NUT-04's {@code null}, in an {@code int}. */
    public static final int NO_EXPIRY = 0;

    /**
     * Anything at or above this is already a Unix timestamp (2001-09-09). A relative figure of this
     * size would be thirty years, which no gateway issues.
     */
    static final long ABSOLUTE_THRESHOLD = 1_000_000_000L;

    private QuoteExpiry() {
    }

    /**
     * The deadline of a quote the gateway has just created, whose relative figure therefore counts
     * from now whichever kind it is.
     *
     * @param gatewayExpiry the gateway's figure, or null when the quote has no deadline
     * @return the absolute Unix timestamp in seconds, or {@link #NO_EXPIRY}
     */
    public static int ofNewQuote(Integer gatewayExpiry) {
        return absolute(gatewayExpiry, Optional.empty());
    }

    /**
     * The deadline of an existing quote, as its gateway now reports it.
     *
     * @param gateway the quote's gateway
     * @param quoteId the quote
     * @return the absolute Unix timestamp in seconds, or {@link #NO_EXPIRY}
     */
    public static int ofQuote(Gateway gateway, String quoteId) {
        return absolute(gateway.getPaymentExpiry(quoteId), createdAt(gateway, quoteId));
    }

    /**
     * @param gatewayExpiry the gateway's figure: a TTL from creation, seconds remaining, an absolute
     *                      timestamp, or null for no deadline
     * @param createdAt     when the gateway created the quote; present exactly when the figure is
     *                      a TTL, absent when it counts from now
     * @return the absolute Unix timestamp in seconds, or {@link #NO_EXPIRY}
     */
    static int absolute(Integer gatewayExpiry, Optional<Instant> createdAt) {
        if (gatewayExpiry == null) {
            return NO_EXPIRY;
        }
        if (gatewayExpiry >= ABSOLUTE_THRESHOLD) {
            return gatewayExpiry;
        }
        // A negative figure is a deadline already passed; treat it as zero seconds left.
        long seconds = Math.max(0L, gatewayExpiry);
        Instant from = createdAt.orElseGet(Instant::now);
        return clampToInt(from.getEpochSecond() + seconds);
    }

    /**
     * When the gateway says the quote was created, or empty when it does not track that. A status
     * check must still answer when the lookup fails, so a failure degrades the expiry (it then
     * counts from now) rather than the response, and is logged: a failing lookup otherwise shows
     * up only as a deadline that moves forward on every poll.
     */
    static Optional<Instant> createdAt(Gateway gateway, String quoteId) {
        try {
            return Optional.ofNullable(gateway.getCreatedAt(quoteId));
        } catch (RuntimeException unavailable) {
            log.warn("quote_expiry created_at_unavailable quote_id={} gateway={} reason={}",
                    quoteId, gateway.getClass().getSimpleName(), unavailable.toString());
            return Optional.empty();
        }
    }

    /** The DTO field is an {@code int}; saturate rather than wrap to a negative past. */
    private static int clampToInt(long epochSecond) {
        return epochSecond > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) epochSecond;
    }
}
