package xyz.tcheeric.cashu.mint.rest.spec003;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
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

    @Autowired
    ApplicationContext applicationContext;

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
     * A sweep nobody calls is not a safety net.
     *
     * <p>Asserting that the bean exists and carries {@code @Scheduled} would be
     * close to tautological: {@code @Autowired} already fails the context if the
     * bean is missing, and reading the annotation is just reflection over this
     * change's own source. Neither shows that anything will ever invoke it.
     *
     * <p>So this asks Spring's {@link ScheduledAnnotationBeanPostProcessor} for
     * the tasks it actually registered, which is the list the scheduler drives.
     * If {@code @EnableScheduling} were removed, or the sweep's interval
     * property resolved to something unparseable, the bean and its annotation
     * would look perfectly healthy and this is the assertion that would fail.
     */
    @Test
    void theReconcilerIsAScheduledBeanInTheRealContext() {
        assertThat(scheduledTaskTargets())
                .as("the scheduler must hold a registered task for the sweep, or nothing "
                        + "ever drives it and the reconciler is decorative")
                .anyMatch(task -> task.contains(VoucherFundingReconciler.class.getName())
                        && task.contains("reconcileTick"));
    }

    /**
     * The target methods of every task the scheduler has actually registered,
     * as {@code Class#method} strings.
     *
     * <p>Reads the fixed-rate, fixed-delay and cron sets rather than a single
     * one, so moving the sweep between trigger styles does not silently empty
     * the assertion.
     *
     * <p>Matches on the runnable's {@code toString}, which is the method
     * reference, rather than unwrapping to the target bean: Spring wraps the
     * runnable (for observability and error handling), so an
     * {@code instanceof ScheduledMethodRunnable} filter silently discards every
     * task and leaves an empty list that no {@code anyMatch} can ever satisfy.
     * That is exactly the vacuous-assertion trap this test was written to avoid.
     */
    private java.util.List<String> scheduledTaskTargets() {
        ScheduledAnnotationBeanPostProcessor processor =
                applicationContext.getBean(ScheduledAnnotationBeanPostProcessor.class);
        return processor.getScheduledTasks().stream()
                .map(org.springframework.scheduling.config.ScheduledTask::getTask)
                .map(org.springframework.scheduling.config.Task::getRunnable)
                .map(Object::toString)
                .toList();
    }
}
