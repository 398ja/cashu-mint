package xyz.tcheeric.cashu.mint.rest.spec003;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import xyz.tcheeric.cashu.mint.jpa.VoucherFundingReconciler;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuoteEntity;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.rest.spec003.support.AbstractVoucherDurableIT;
import xyz.tcheeric.cashu.mint.rest.spec003.support.VoucherTestSupport;
import xyz.tcheeric.cashu.mint.webhook.PaymentNotification;
import xyz.tcheeric.cashu.mint.webhook.QuoteStatusUpdater;
import xyz.tcheeric.cashu.mint.webhook.WebhookOutcome;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #459 — proves the fix is actually wired in the assembled application,
 * not merely correct in the module that declares it.
 *
 * <p>This test exists because of the specific way this fix could be dead on
 * arrival. {@code QuoteStatusUpdater} takes its collaborators as
 * {@code @Autowired(required = false)} so unit-test contexts can construct it
 * with nulls. That same leniency means a {@code VoucherFundingResolver} which
 * is not visible to the webhook module in the real context would be silently
 * {@code null}, half A would never run, and every unit test would still pass —
 * the failure would surface only as another stranded voucher in production.
 *
 * <p>That is not a hypothetical: {@code imani-wallet-lib#66} shipped the same
 * week as a fix that was correct in its library and unreachable in the
 * deployment, for want of exactly this kind of test. So these assertions run
 * against the real {@link xyz.tcheeric.cashu.mint.rest.CashuMintRestApplication}
 * context with the beans Spring actually chose.
 */
class VoucherFundingWiringIT extends AbstractVoucherDurableIT {

    /** Must be the container-managed instance, not a hand-built one. */
    @Autowired
    QuoteStatusUpdater quoteStatusUpdater;

    @Autowired
    VoucherFundingReconciler reconciler;

    /**
     * The end-to-end guarantee, through the real bean graph: a payment webhook
     * for a voucher quote funds it, with no client mint request anywhere.
     */
    @Test
    void anAcceptedPaymentFundsTheVoucherThroughTheRealBeanGraph() {
        String quoteId = VoucherTestSupport.newQuoteId("wiring-paid");
        VoucherQuoteEntity quote = VoucherTestSupport.unfundedQuote(quoteId, 20_000L);
        // charged_amount is what the customer actually pays (the fee).
        quote.setChargedAmount(50L);
        quote.setFee(50L);
        voucherQuoteJpaRepository.save(quote);

        WebhookOutcome outcome = quoteStatusUpdater.record(PaymentNotification.builder()
                .quoteId(quoteId)
                .paymentMethod("bolt11")
                .amount(50)
                .preimage("preimage-" + quoteId)
                .receiptId("evt-" + quoteId)
                .build());

        assertThat(outcome.isAccepted()).isTrue();

        VoucherQuoteEntity funded = voucherQuoteJpaRepository.findById(quoteId).orElseThrow();
        assertThat(funded.getLifecycleState())
                .as("the webhook must fund the quote; if the resolver were unwired "
                        + "this would still be UNFUNDED and the fix would be dead in production")
                .isEqualTo(VoucherLifecycleState.FUNDED);
        assertThat(funded.getFundingId()).isNotNull();
    }

    /**
     * A reconciler that is not a bean is a safety net that never runs. The
     * scheduler would then be the only thing standing between a leaked payment
     * and a stranded voucher, and it would not exist.
     */
    @Test
    void theReconcilerIsAScheduledBeanInTheRealContext() {
        assertThat(reconciler)
                .as("VoucherFundingReconciler must be wired, or nothing sweeps")
                .isNotNull();
        assertThat(ReflectionSupport.hasScheduledMethod(reconciler.getClass()))
                .as("the sweep must be @Scheduled; a bean nobody calls is not a safety net")
                .isTrue();
    }

    /** Tiny helper kept local: the assertion is about this class, not a shared concern. */
    private static final class ReflectionSupport {
        static boolean hasScheduledMethod(Class<?> type) {
            Class<?> target = type.getName().contains("$$") ? type.getSuperclass() : type;
            for (var method : target.getDeclaredMethods()) {
                if (method.isAnnotationPresent(
                        org.springframework.scheduling.annotation.Scheduled.class)) {
                    return true;
                }
            }
            return false;
        }
    }
}
