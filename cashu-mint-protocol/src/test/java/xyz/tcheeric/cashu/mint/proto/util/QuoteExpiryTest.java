package xyz.tcheeric.cashu.mint.proto.util;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * NUT-04 {@code expiry} is an absolute Unix timestamp; the gateways return a relative TTL
 * (cashu-mint#494).
 */
class QuoteExpiryTest {

    private static final Instant CREATED = Instant.parse("2026-09-26T14:00:00Z");

    // A relative TTL is counted from the quote's creation.
    @Test
    void relativeTtlBecomesCreationPlusTtl() {
        assertThat(QuoteExpiry.absolute(60, CREATED)).isEqualTo((int) CREATED.getEpochSecond() + 60);
    }

    // With no creation time the quote is taken to be created now, which is right for a response
    // to the request that created it.
    @Test
    void relativeTtlWithoutCreationCountsFromNow() {
        long before = Instant.now().getEpochSecond();
        int expiry = QuoteExpiry.absolute(60, null);
        long after = Instant.now().getEpochSecond();

        assertThat((long) expiry).isBetween(before + 60, after + 60);
    }

    // A value that is already a timestamp is passed through, so a gateway that starts returning
    // one keeps working.
    @Test
    void absoluteTimestampPassesThrough() {
        int absolute = (int) CREATED.plusSeconds(900).getEpochSecond();
        assertThat(QuoteExpiry.absolute(absolute, CREATED)).isEqualTo(absolute);
    }

    // Null, zero and negative all mean "no expiry", reported as 0.
    @Test
    void missingOrNonPositiveMeansNoExpiry() {
        assertThat(QuoteExpiry.absolute(null, CREATED)).isZero();
        assertThat(QuoteExpiry.absolute(0, CREATED)).isZero();
        assertThat(QuoteExpiry.absolute(-5, CREATED)).isZero();
    }

    // Whatever the TTL, the result is never a timestamp in the past that a wallet would refuse,
    // and it is always read as absolute by clients that guess (value > now / 2).
    @Test
    void resultIsAlwaysAFutureAbsoluteTimestampForAFreshQuote() {
        long before = Instant.now().getEpochSecond();
        int expiry = QuoteExpiry.absolute(1, null);

        assertThat((long) expiry).isGreaterThan(before);
        assertThat((long) expiry).isGreaterThan(before / 2);
    }

    // A gateway that cannot say when the quote was created yields null rather than failing the
    // status response.
    @Test
    void createdAtToleratesAGatewayThatThrows() {
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.getCreatedAt("q")).thenThrow(new IllegalStateException("lookup failed"));

        assertThat(QuoteExpiry.createdAt(gateway, "q")).isNull();
    }
}
