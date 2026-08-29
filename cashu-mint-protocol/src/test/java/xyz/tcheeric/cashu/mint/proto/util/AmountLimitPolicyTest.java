package xyz.tcheeric.cashu.mint.proto.util;

import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.service.MintInfoService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintInfoService;
import xyz.tcheeric.cashu.mint.proto.util.MintCapabilityProperties.PaymentMethodLimits;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #390 — the advertised amount limits must be the enforced ones.
 *
 * <p>The limits were configuration that only {@code /v1/info} read, so
 * {@code max_amount} was a number wallets trusted and the mint ignored. These
 * tests pin both halves and, crucially, the link between them: the number served
 * from {@code /v1/info} is read back out of the response and used as the input
 * that must be rejected, so the two cannot drift into different values without
 * failing here.
 */
class AmountLimitPolicyTest {

    private static final String UNIT = "sat";
    private static final int MIN_AMOUNT = 10;
    private static final int MAX_AMOUNT = 500;

    // An amount inside the advertised range is accepted on both paths.
    @Test
    void amountsInsideTheAdvertisedRangeAreAccepted() {
        AmountLimitPolicy policy = policyWith(MIN_AMOUNT, MAX_AMOUNT);

        assertThatCode(() -> policy.requireWithinMintLimits(MAX_AMOUNT, UNIT))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.requireWithinMeltLimits(MIN_AMOUNT, UNIT))
                .doesNotThrowAnyException();
    }

    // A mint request above the advertised max_amount is rejected with the typed
    // amount_outside_limit_range code rather than silently issued.
    @Test
    void mintAmountAboveTheAdvertisedMaximumIsRejected() {
        AmountLimitPolicy policy = policyWith(MIN_AMOUNT, MAX_AMOUNT);

        assertThatThrownBy(() -> policy.requireWithinMintLimits(MAX_AMOUNT + 1, UNIT))
                .isInstanceOf(CashuErrorException.class)
                .extracting(error -> ((CashuErrorException) error).getErrorCode())
                .isEqualTo(CashuErrorCode.amount_outside_limit_range);
    }

    // A mint request below the advertised min_amount is rejected too.
    @Test
    void mintAmountBelowTheAdvertisedMinimumIsRejected() {
        AmountLimitPolicy policy = policyWith(MIN_AMOUNT, MAX_AMOUNT);

        assertThatThrownBy(() -> policy.requireWithinMintLimits(MIN_AMOUNT - 1, UNIT))
                .isInstanceOf(CashuErrorException.class)
                .extracting(error -> ((CashuErrorException) error).getErrorCode())
                .isEqualTo(CashuErrorCode.amount_outside_limit_range);
    }

    // A melt invoice above the advertised max_amount is rejected at quote time.
    @Test
    void meltAmountOutsideTheAdvertisedRangeIsRejected() {
        AmountLimitPolicy policy = policyWith(MIN_AMOUNT, MAX_AMOUNT);

        assertThatThrownBy(() -> policy.requireWithinMeltLimits(MAX_AMOUNT + 1, UNIT))
                .isInstanceOf(CashuErrorException.class)
                .extracting(error -> ((CashuErrorException) error).getErrorCode())
                .isEqualTo(CashuErrorCode.amount_outside_limit_range);
    }

    // A unit the mint never advertised limits for is refused rather than waved
    // through: an unmatched request would reopen the unenforced-limit hole.
    @Test
    void unadvertisedUnitIsRefused() {
        AmountLimitPolicy policy = policyWith(MIN_AMOUNT, MAX_AMOUNT);

        assertThatThrownBy(() -> policy.requireWithinMintLimits(MIN_AMOUNT, "usd"))
                .isInstanceOf(CashuErrorException.class)
                .extracting(error -> ((CashuErrorException) error).getErrorCode())
                .isEqualTo(CashuErrorCode.unit_not_supported);
    }

    // The link that closes the loop: the max_amount a wallet reads from
    // /v1/info, plus one, must be the amount the mint refuses. If the advertised
    // number and the enforced number ever diverge, this fails.
    @Test
    void theAdvertisedMaximumIsTheEnforcedMaximum() throws Exception {
        MintCapabilityProperties capabilities = capabilitiesWith(MIN_AMOUNT, MAX_AMOUNT);
        MintInfoService infoService =
                new DefaultMintInfoService(new MintIdentityProperties(), capabilities);

        MintInfo.Nut.Method advertised = infoService.getMintInfo().getNuts().get("4")
                .getMethods().getFirst();
        AmountLimitPolicy policy = new AmountLimitPolicy(capabilities);

        assertThatCode(() -> policy.requireWithinMintLimits(advertised.getMaxAmount(), advertised.getUnit()))
                .as("the advertised maximum itself must be mintable")
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireWithinMintLimits(advertised.getMaxAmount() + 1L, advertised.getUnit()))
                .as("one satoshi over the advertised maximum must be refused")
                .isInstanceOf(CashuErrorException.class);
        assertThat(advertised.getMinAmount()).isEqualTo(MIN_AMOUNT);
    }

    private AmountLimitPolicy policyWith(int minAmount, int maxAmount) {
        return new AmountLimitPolicy(capabilitiesWith(minAmount, maxAmount));
    }

    private MintCapabilityProperties capabilitiesWith(int minAmount, int maxAmount) {
        MintCapabilityProperties capabilities = new MintCapabilityProperties();
        capabilities.setMintMethods(List.of(limits(minAmount, maxAmount)));
        capabilities.setMeltMethods(List.of(limits(minAmount, maxAmount)));
        return capabilities;
    }

    private PaymentMethodLimits limits(int minAmount, int maxAmount) {
        PaymentMethodLimits limits = new PaymentMethodLimits();
        limits.setMethod("bolt11");
        limits.setUnit(UNIT);
        limits.setMinAmount(minAmount);
        limits.setMaxAmount(maxAmount);
        return limits;
    }
}
