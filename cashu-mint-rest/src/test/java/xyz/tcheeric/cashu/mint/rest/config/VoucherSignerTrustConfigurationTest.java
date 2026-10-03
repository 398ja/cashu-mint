package xyz.tcheeric.cashu.mint.rest.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherIssuerBinding;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherIssuerBindingMode;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherSignerTrustList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cashu-mint#527: what a deployment gets from the voucher signer properties, booted in a real
 * context so the binding and the boot guard are the ones Spring actually produces.
 */
@DisplayName("the voucher signer trust configuration")
class VoucherSignerTrustConfigurationTest {

    private static final String GATEWAY_KEY =
            "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";
    private static final String MERCHANT_KEY =
            "c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(VoucherSignerTrustConfiguration.class);

    @AfterEach
    void clearInstalledBinding() {
        MintIntegrityContext.clear();
    }

    /** With nothing configured the mint boots in log mode and installs that binding for the swap path. */
    @Test
    void defaultsToLogAndInstallsTheBinding() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            VoucherIssuerBinding binding = context.getBean(VoucherIssuerBinding.class);
            assertThat(binding.mode()).isEqualTo(VoucherIssuerBindingMode.LOG);
            assertThat(MintIntegrityContext.voucherIssuerBinding()).isSameAs(binding);
        });
    }

    /** Enforce with no keys configured would refuse every signed voucher, so the mint refuses to boot. */
    @Test
    void refusesToBootInEnforceWithNoKeys() {
        contextRunner.withPropertyValues("cashu.mint.voucher.issuer-binding=enforce").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("issuer-binding=enforce");
        });
    }

    /** Unset environment placeholders resolve to blank and do not count as configured keys. */
    @Test
    void refusesToBootInEnforceWithOnlyBlankKeys() {
        contextRunner.withPropertyValues(
                        "cashu.mint.voucher.issuer-binding=enforce",
                        "cashu.mint.voucher.trusted-signers=",
                        "cashu.mint.voucher.issuer-keys.corner-cafe=")
                .run(context -> assertThat(context).hasFailed());
    }

    /** Enforce boots once a trusted signer is configured, in either encoding. */
    @Test
    void bootsInEnforceWithATrustedSigner() {
        contextRunner.withPropertyValues(
                        "cashu.mint.voucher.issuer-binding=enforce",
                        "cashu.mint.voucher.trusted-signers=02" + GATEWAY_KEY + "," + MERCHANT_KEY)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    VoucherSignerTrustList trustList = context.getBean(VoucherSignerTrustList.class);
                    assertThat(trustList.trustedSignerCount()).isEqualTo(2);
                    assertThat(trustList.trusts("any-merchant", GATEWAY_KEY)).isTrue();
                });
    }

    /** Issuer keys and trusted signers are parsed into the one trust list every check shares. */
    @Test
    void issuerKeysFeedTheSharedTrustList() {
        contextRunner.withPropertyValues(
                        "cashu.mint.voucher.issuer-binding=enforce",
                        "cashu.mint.voucher.issuer-keys.corner-cafe=" + MERCHANT_KEY.toUpperCase())
                .run(context -> {
                    VoucherSignerTrustList trustList = context.getBean(VoucherSignerTrustList.class);
                    assertThat(trustList.registeredKeyFor("corner-cafe")).contains(MERCHANT_KEY);
                    assertThat(trustList.trusts("corner-cafe", MERCHANT_KEY)).isTrue();
                });
    }

    /** A malformed key fails the boot rather than silently trusting nothing. */
    @Test
    void refusesToBootWithAMalformedKey() {
        contextRunner.withPropertyValues("cashu.mint.voucher.trusted-signers=not-a-key")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalArgumentException.class);
                });
    }

    /** Off mode boots with nothing configured, and is the documented escape hatch. */
    @Test
    void bootsInOffWithNoKeys() {
        contextRunner.withPropertyValues("cashu.mint.voucher.issuer-binding=off")
                .run(context -> assertThat(context.getBean(VoucherIssuerBinding.class).mode())
                        .isEqualTo(VoucherIssuerBindingMode.OFF));
    }
}
