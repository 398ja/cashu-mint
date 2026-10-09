package xyz.tcheeric.cashu.mint.proto.voucher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** cashu-mint#540: the cap a coupon's signed terms give, and the declaration's wire form. */
class VoucherRedemptionTermsTest {

    @Test
    void capIsTheIssuedSatsFromFaceAndRatio() {
        // MINIMAL: EUR 5.00 on 10 sats.
        assertEquals(10L, VoucherTerms.cap(500L, 50.0));
        // PROPORTIONAL at ~EUR 71k/BTC: ratio is face/sats exactly as issuance computed it.
        assertEquals(7000L, VoucherTerms.cap(500L, 500.0 / 7000.0));
        // No ratio means 1:1.
        assertEquals(1000L, VoucherTerms.cap(1000L, null));
        // A ratio that does not divide rounds up, towards the holder.
        assertEquals(4L, VoucherTerms.cap(10L, 3.0));
    }

    /**
     * Float noise must not add a sat. EUR 0.17 on 7 sats gives a ratio whose quotient is
     * 7.000000000000001, and a bare ceil would make the cap 8. Pins ROUNDING_SLACK.
     */
    @Test
    void floatNoiseInTheRatioDoesNotRaiseTheCap() {
        assertEquals(7L, VoucherTerms.cap(17L, 17.0 / 7));
        assertEquals(7L, VoucherTerms.cap(34L, 34.0 / 7));
        for (long sats = 1; sats <= 20_000; sats++) {
            for (long face : new long[] {1, 7, 17, 99, 500, 1234, 99_999}) {
                assertEquals(sats, VoucherTerms.cap(face, (double) face / sats), face + "/" + sats);
            }
        }
    }

    @Test
    void anUnstatedOrNonsenseCapIsUnknown() {
        assertNull(VoucherTerms.cap(null, 1.0));
        assertNull(VoucherTerms.cap(0L, 1.0));
        assertNull(VoucherTerms.cap(500L, 0.0));
        assertNull(VoucherTerms.cap(500L, -1.0));
        assertNull(VoucherTerms.cap(500L, Double.NaN));
        assertNull(VoucherTerms.cap(500L, Double.POSITIVE_INFINITY));
        assertNull(VoucherTerms.cap(500L, 1e-300), "an absurd ratio must not overflow into a huge cap");
    }

    @Test
    void theDeclarationParsesVoucherIdAndSats() {
        VoucherRedemptionDeclaration d = VoucherRedemptionDeclaration
                .parse("6f395858-7826-4493-84e5-2a7e907c0820:10").orElseThrow();
        assertEquals("6f395858-7826-4493-84e5-2a7e907c0820", d.voucherId());
        assertEquals(10L, d.sats());
        assertEquals("6f395858", d.voucherIdPrefix());
    }

    @Test
    void noHeaderIsNoDeclaration() {
        assertTrue(VoucherRedemptionDeclaration.parse(null).isEmpty());
        assertTrue(VoucherRedemptionDeclaration.parse("  ").isEmpty());
    }

    @Test
    void aMalformedDeclarationIsRefused() {
        for (String bad : new String[] {"abc", ":10", "abc:", "abc:x", "abc:0", "abc:-5", "a b:1", "x".repeat(65) + ":1"}) {
            assertThrows(IllegalArgumentException.class, () -> VoucherRedemptionDeclaration.parse(bad), bad);
        }
    }
}
