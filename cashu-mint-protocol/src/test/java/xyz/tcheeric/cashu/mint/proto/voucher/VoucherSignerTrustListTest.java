package xyz.tcheeric.cashu.mint.proto.voucher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherIssuerBindingMode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parsed signer configuration both voucher checks share (cashu-mint#527).
 */
@DisplayName("the voucher signer trust list")
class VoucherSignerTrustListTest {

    private static final String X_ONLY = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";
    private static final String OTHER = "c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5";

    /** A trusted signer is trusted whatever issuer the voucher names, including none. */
    @Test
    void trustedSignerCoversEveryIssuer() {
        VoucherSignerTrustList list = new VoucherSignerTrustList(Map.of(), List.of(X_ONLY));

        assertThat(list.trusts("any-merchant", X_ONLY)).isTrue();
        assertThat(list.trusts(null, X_ONLY)).isTrue();
    }

    /** An issuer key is trusted only for its own issuer. */
    @Test
    void issuerKeyCoversOnlyItsIssuer() {
        VoucherSignerTrustList list = new VoucherSignerTrustList(Map.of("cafe", X_ONLY), List.of());

        assertThat(list.trusts("cafe", X_ONLY)).isTrue();
        assertThat(list.trusts("bakery", X_ONLY)).isFalse();
        assertThat(list.trusts(null, X_ONLY)).isFalse();
    }

    /** A presented key that is malformed, null or unknown is simply not trusted. */
    @Test
    void untrustedOrMalformedKeysAreNotTrusted() {
        VoucherSignerTrustList list = new VoucherSignerTrustList(Map.of("cafe", X_ONLY), List.of(X_ONLY));

        assertThat(list.trusts("cafe", OTHER)).isFalse();
        assertThat(list.trusts("cafe", null)).isFalse();
        assertThat(list.trusts("cafe", "not-hex")).isFalse();
        assertThat(list.trusts("cafe", "04" + X_ONLY)).isFalse();
    }

    /** A compressed configured key is matched by the x-only key a voucher carries, and the reverse. */
    @Test
    void compressedAndXOnlyFormsAreTheSameKey() {
        VoucherSignerTrustList list = new VoucherSignerTrustList(
                Map.of("cafe", "03" + X_ONLY.toUpperCase()), List.of());

        assertThat(list.trusts("CAFE", X_ONLY)).isTrue();
        assertThat(list.trusts("cafe", "02" + X_ONLY)).isTrue();
        assertThat(list.registeredKeyFor("Cafe")).contains(X_ONLY);
    }

    /** An unset environment placeholder resolves to blank, which means not configured rather than a typo. */
    @Test
    void blankEntriesAreSkipped() {
        VoucherSignerTrustList list = new VoucherSignerTrustList(Map.of("cafe", " "), List.of("", " "));

        assertThat(list.isEmpty()).isTrue();
        assertThat(list.issuerKeyCount()).isZero();
        assertThat(list.trustedSignerCount()).isZero();
    }

    /** A malformed configured key fails loudly instead of silently trusting nothing. */
    @Test
    void malformedConfiguredKeysAreRefused() {
        assertThatThrownBy(() -> new VoucherSignerTrustList(Map.of(), List.of("abc")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VoucherSignerTrustList(Map.of("cafe", "05" + X_ONLY), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** The refusal names the property and position but never repeats the value, which might be a private key. */
    @Test
    void malformedKeyErrorsNameThePropertyAndWithholdTheValue() {
        String privateKeyLookalike = "a".repeat(63);

        assertThatThrownBy(() -> new VoucherSignerTrustList(Map.of(), List.of(X_ONLY, privateKeyLookalike)))
                .hasMessageContaining("cashu.mint.voucher.trusted-signers[1]")
                .hasMessageNotContaining(privateKeyLookalike);
        assertThatThrownBy(() -> new VoucherSignerTrustList(Map.of("cafe", privateKeyLookalike), List.of()))
                .hasMessageContaining("cashu.mint.voucher.issuer-keys.cafe")
                .hasMessageNotContaining(privateKeyLookalike);
    }

    /** The mint's own voucher signing key is trusted for any issuer, like a configured trusted signer. */
    @Test
    void mintOwnSignerIsTrustedForAnyIssuer() {
        VoucherSignerTrustList list = new VoucherSignerTrustList(Map.of(), List.of(),
                Map.of("voucher.mint.issuerPublicKey", X_ONLY));

        assertThat(list.trusts("whatever-merchant", X_ONLY)).isTrue();
        assertThat(list.isEmpty()).isFalse();
    }

    /** Enforce with nothing configured would refuse every signed voucher, so it cannot be built. */
    @Test
    void enforceWithNothingTrustedRefusesToBuild() {
        assertThatThrownBy(() -> new VoucherIssuerBinding(VoucherIssuerBindingMode.ENFORCE,
                VoucherSignerTrustList.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuer-binding=enforce");
    }

    /** Log and off are safe with nothing configured: log reports, off ignores. */
    @Test
    void logAndOffBuildWithNothingTrusted() {
        assertThat(new VoucherIssuerBinding(VoucherIssuerBindingMode.LOG, VoucherSignerTrustList.empty())
                .mode()).isEqualTo(VoucherIssuerBindingMode.LOG);
        assertThat(new VoucherIssuerBinding(VoucherIssuerBindingMode.OFF, VoucherSignerTrustList.empty())
                .mode()).isEqualTo(VoucherIssuerBindingMode.OFF);
    }

    /** An unconfigured mint gets the property's own default, log. */
    @Test
    void unconfiguredBindingLogs() {
        assertThat(VoucherIssuerBinding.unconfigured().mode()).isEqualTo(VoucherIssuerBindingMode.LOG);
    }
}
