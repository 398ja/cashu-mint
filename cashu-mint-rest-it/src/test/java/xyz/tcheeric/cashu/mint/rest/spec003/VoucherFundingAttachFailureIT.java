package xyz.tcheeric.cashu.mint.rest.spec003;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuoteEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.WebhookEventJpaRepository;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFunding;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherFundingResolver;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuote;
import xyz.tcheeric.cashu.mint.rest.spec003.support.AbstractVoucherDurableIT;
import xyz.tcheeric.cashu.mint.rest.spec003.support.VoucherTestSupport;
import xyz.tcheeric.cashu.mint.webhook.PaymentNotification;
import xyz.tcheeric.cashu.mint.webhook.QuoteStatusUpdater;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #459 — what actually happens to the payment record when the funding
 * attach blows up inside the webhook's transaction.
 *
 * <p>The unit test for this path uses mocks and therefore has no transaction
 * at all, so it can only prove that {@code attachFunding} swallows the
 * exception — not what the caller and the database end up seeing. Those are
 * different questions, and only the second one matters: {@code record} is
 * {@code @Transactional}, and Spring marks a transaction rollback-only when a
 * {@code RuntimeException} escapes a participating call, whether or not
 * somebody catches it afterwards.
 *
 * <p>This test pins the real answer so the javadoc on {@code attachFunding}
 * cannot quietly become a lie.
 */
@Import(VoucherFundingAttachFailureIT.FailingResolverConfig.class)
class VoucherFundingAttachFailureIT extends AbstractVoucherDurableIT {

    /** Stands in for a funding store that is refusing to answer. */
    @TestConfiguration
    static class FailingResolverConfig {
        @Bean
        @Primary
        VoucherFundingResolver failingResolver() {
            return new VoucherFundingResolver() {
                @Override
                public Optional<VoucherFunding> resolveForQuote(VoucherQuote quote) {
                    throw new IllegalStateException("funding store unreachable");
                }
            };
        }
    }

    @Autowired
    QuoteStatusUpdater quoteStatusUpdater;

    @Autowired
    WebhookEventJpaRepository webhookEvents;

    /**
     * The behaviour that decides whether the money stays traceable.
     *
     * <p>Verified behaviour: the {@code accepted} event <strong>survives</strong>
     * and the delivery is still reported accepted. That is what
     * {@code attachFunding}'s javadoc promises, and it is only true because the
     * exception is caught before it can escape a call participating in
     * {@code record}'s transaction — had it propagated, Spring would have marked
     * the transaction rollback-only and the payment record would have vanished
     * while the caller was told {@code 200}. That combination is issue #462's
     * shape: money taken and the mint does not know.
     *
     * <p>Asserted exactly rather than as an either/or, so that if the catch is
     * ever narrowed or moved, this fails instead of quietly accepting the
     * rollback path.
     */
    @Test
    void aFailedAttachMustNotSilentlyDiscardThePaymentRecord() {
        String quoteId = VoucherTestSupport.newQuoteId("attach-fail");
        VoucherQuoteEntity quote = VoucherTestSupport.unfundedQuote(quoteId, 20_000L);
        quote.setChargedAmount(50L);
        quote.setFee(50L);
        voucherQuoteJpaRepository.save(quote);

        PaymentNotification notification = PaymentNotification.builder()
                .quoteId(quoteId)
                .paymentMethod("bolt11")
                .amount(50)
                .preimage("preimage-" + quoteId)
                .receiptId("evt-" + quoteId)
                .build();

        assertThat(quoteStatusUpdater.record(notification).isAccepted())
                .as("the payment is real; a resolver outage must not reject the delivery")
                .isTrue();

        assertThat(webhookEvents.findAcceptedByQuoteId(
                quoteId, org.springframework.data.domain.Limit.of(1)))
                .as("the accepted event must survive, or nothing can ever heal this quote "
                        + "and the provider will not retry")
                .hasSize(1);

        VoucherQuoteEntity after = voucherQuoteJpaRepository.findById(quoteId).orElseThrow();
        assertThat(after.getLifecycleState())
                .as("the attach failed, so the quote must still be awaiting funding")
                .isEqualTo(VoucherLifecycleState.UNFUNDED);
        assertThat(after.getFundingId())
                .as("FUNDED with no funding row would break the SC-001 invariant")
                .isNull();
    }

    /**
     * If the event does survive a failed attach, the reconciler must be able to
     * find it — that is the entire basis for calling the sweep a safety net.
     */
    @Test
    void aSurvivingPaymentRecordIsVisibleToTheReconcilerSweep() {
        String quoteId = VoucherTestSupport.newQuoteId("attach-fail-sweepable");
        VoucherQuoteEntity quote = VoucherTestSupport.unfundedQuote(quoteId, 20_000L);
        quote.setChargedAmount(50L);
        quote.setFee(50L);
        voucherQuoteJpaRepository.save(quote);

        quoteStatusUpdater.record(PaymentNotification.builder()
                    .quoteId(quoteId)
                    .paymentMethod("bolt11")
                    .amount(50)
                    .preimage("preimage-" + quoteId)
                    .receiptId("evt-" + quoteId)
                    .build());

        assertThat(voucherQuoteJpaRepository.countPaidUnfunded())
                .as("the stranded quote must be visible to the gauge, or the alert stays silent "
                        + "while the money sits unissued")
                .isEqualTo(1L);
    }
}
