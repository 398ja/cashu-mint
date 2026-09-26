package xyz.tcheeric.cashu.mint.proto.util;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * NUT-04 {@code expiry} is an absolute Unix timestamp; the gateways return a relative figure,
 * either a TTL from creation or the seconds remaining (cashu-mint#494, #503).
 */
class QuoteExpiryTest {

    private static final Instant CREATED = Instant.parse("2026-09-26T14:00:00Z");

    private static Gateway gateway(Integer figure, Instant createdAt) {
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.getPaymentExpiry("q")).thenReturn(figure);
        when(gateway.getCreatedAt("q")).thenReturn(createdAt);
        return gateway;
    }

    // A TTL gateway (phoenixd) tracks creation, so its deadline is creation plus the TTL.
    @Test
    void aTtlIsCountedFromCreation() {
        assertThat(QuoteExpiry.ofQuote(gateway(60, CREATED), "q"))
                .isEqualTo((int) CREATED.getEpochSecond() + 60);
    }

    // A remaining-seconds gateway (cash, Stripe) tracks no creation, so its figure counts from
    // now.
    @Test
    void secondsRemainingAreCountedFromNow() {
        long before = Instant.now().getEpochSecond();
        int expiry = QuoteExpiry.ofQuote(gateway(300, null), "q");
        long after = Instant.now().getEpochSecond();

        assertThat((long) expiry).isBetween(before + 300, after + 300);
    }

    // #503: a remaining-seconds gateway reports 0 once its quote has lapsed. That is a deadline
    // now, not "never expires", which clients read 0 as, so an expired cash quote is no longer
    // offered as payable forever.
    @Test
    void zeroSecondsRemainingIsExpiredNotNeverExpiring() {
        long before = Instant.now().getEpochSecond();
        int expiry = QuoteExpiry.ofQuote(gateway(0, null), "q");
        long after = Instant.now().getEpochSecond();

        assertThat(expiry).isNotEqualTo(QuoteExpiry.NO_EXPIRY);
        assertThat((long) expiry).isBetween(before, after);
    }

    // A negative figure is a deadline already passed and reads the same as zero, never as none.
    @Test
    void aNegativeFigureIsExpired() {
        assertThat(QuoteExpiry.absolute(-5, Optional.of(CREATED))).isEqualTo((int) CREATED.getEpochSecond());
    }

    // Only a gateway that reports no figure at all has a quote without a deadline.
    @Test
    void noFigureMeansNoDeadline() {
        assertThat(QuoteExpiry.ofQuote(gateway(null, CREATED), "q")).isEqualTo(QuoteExpiry.NO_EXPIRY);
        assertThat(QuoteExpiry.ofNewQuote(null)).isEqualTo(QuoteExpiry.NO_EXPIRY);
    }

    // A new quote's figure counts from now, whichever kind the gateway returns.
    @Test
    void aNewQuoteCountsFromNow() {
        long before = Instant.now().getEpochSecond();
        int expiry = QuoteExpiry.ofNewQuote(60);
        long after = Instant.now().getEpochSecond();

        assertThat((long) expiry).isBetween(before + 60, after + 60);
    }

    // A figure that is already a timestamp is passed through, so a gateway that starts returning
    // one keeps working.
    @Test
    void anAbsoluteTimestampPassesThrough() {
        int absolute = (int) CREATED.plusSeconds(900).getEpochSecond();
        assertThat(QuoteExpiry.ofQuote(gateway(absolute, CREATED), "q")).isEqualTo(absolute);
    }

    // A gateway whose creation lookup fails still yields a deadline (counted from now) rather
    // than failing the status response.
    @Test
    void aFailingCreationLookupCountsFromNow() {
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.getPaymentExpiry("q")).thenReturn(60);
        when(gateway.getCreatedAt("q")).thenThrow(new IllegalStateException("lookup failed"));

        long before = Instant.now().getEpochSecond();
        int expiry = QuoteExpiry.ofQuote(gateway, "q");

        assertThat((long) expiry).isGreaterThanOrEqualTo(before + 60);
        assertThat(QuoteExpiry.createdAt(gateway, "q")).isEmpty();
    }
}
