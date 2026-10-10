package xyz.tcheeric.cashu.mint.rest.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherRedemptionLedger;
import xyz.tcheeric.cashu.mint.proto.voucher.InstalledVoucherIssuerBinding;
import xyz.tcheeric.cashu.mint.proto.voucher.InstalledVoucherRedemptionCap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * cashu-mint#540 review §1.2b: the cap is pinned to the coupon's signed terms, which only mean
 * something when the signer is bound. Under {@code issuer-binding=log} any self-signed blob naming
 * a victim's voucher id reaches the ledger, so {@code redemption-cap=enforce} refuses to boot unless
 * {@code issuer-binding=enforce}.
 */
class VoucherRedemptionCapConfigurationTest {

    private static final String GATEWAY_KEY =
            "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(VoucherSignerTrustConfiguration.class, VoucherRedemptionCapConfiguration.class)
            .withBean(VoucherRedemptionLedger.class, () -> mock(VoucherRedemptionLedger.class))
            .withPropertyValues("cashu.mint.voucher.redeemer-tokens=core-token");

    @AfterEach
    void clearInstalled() {
        InstalledVoucherIssuerBinding.install(null);
        InstalledVoucherRedemptionCap.install(null);
    }

    @Test
    void enforceRefusesToBootUnderAnIssuerBindingInLogMode() {
        contextRunner.withPropertyValues("cashu.mint.voucher.redemption-cap=enforce",
                        "cashu.mint.voucher.issuer-binding=log")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("issuer-binding=enforce");
                });
    }

    @Test
    void enforceBootsUnderAnEnforcedIssuerBinding() {
        contextRunner.withPropertyValues("cashu.mint.voucher.redemption-cap=enforce",
                        "cashu.mint.voucher.issuer-binding=enforce",
                        "cashu.mint.voucher.trusted-signers=" + GATEWAY_KEY)
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** Log mode is the rollout default and must boot whatever the binding, with no token at all. */
    @Test
    void logBootsUnderAnyBindingAndWithoutTokens() {
        new ApplicationContextRunner()
                .withUserConfiguration(VoucherSignerTrustConfiguration.class, VoucherRedemptionCapConfiguration.class)
                .withPropertyValues("cashu.mint.voucher.redemption-cap=log")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
