package xyz.tcheeric.cashu.mint.rest.spec003;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import xyz.tcheeric.cashu.mint.jpa.VoucherFundingReconciler;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuoteEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.WebhookEventEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.WebhookEventJpaRepository;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.rest.spec003.support.AbstractVoucherDurableIT;
import xyz.tcheeric.cashu.mint.rest.spec003.support.VoucherTestSupport;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #459 — drives {@link VoucherFundingReconciler} and its sweep query
 * against live Postgres.
 *
 * <p>The unit tests mock the repository, so they prove the reconciler's
 * decisions but not the native SQL those decisions rest on. That distinction
 * is the whole lesson of this issue: a fix that is correct in isolation and
 * wrong in the deployment that matters. These tests execute the real query
 * against the real schema.
 */
class VoucherFundingReconcilerIT extends AbstractVoucherDurableIT {

    private static final String PROVIDER = "phoenixd-it";

    @Autowired
    VoucherFundingReconciler reconciler;

    @Autowired
    WebhookEventJpaRepository webhookEvents;

    @BeforeEach
    void cleanWebhookEvents() {
        webhookEvents.deleteAll();
    }

    /**
     * Seeds a voucher quote that was paid for but never funded: the exact
     * shape of the sixty-eight rows found on staging.
     */
    private VoucherQuoteEntity seedPaidButUnfunded(String quoteId, Instant paidAt) {
        VoucherQuoteEntity quote = VoucherTestSupport.unfundedQuote(quoteId, 20_000L);
        voucherQuoteJpaRepository.save(quote);

        WebhookEventEntity event = VoucherTestSupport.acceptedWebhookEvent(
                PROVIDER, "evt-" + quoteId, quoteId, 20_000L);
        event.setReceivedAt(paidAt);
        webhookEvents.save(event);
        return quote;
    }

    /**
     * The defect end to end: a quote whose payment was accepted and whose
     * client never returned is funded by the sweep alone, with no mint request.
     */
    @Test
    void sweepFundsAQuoteThatWasPaidForButNeverFunded() {
        String quoteId = VoucherTestSupport.newQuoteId("it-stranded");
        seedPaidButUnfunded(quoteId, Instant.now().minus(Duration.ofHours(1)));

        reconciler.reconcileTick();

        VoucherQuoteEntity healed = voucherQuoteJpaRepository.findById(quoteId).orElseThrow();
        assertThat(healed.getLifecycleState()).isEqualTo(VoucherLifecycleState.FUNDED);
        assertThat(healed.getFundingId())
                .as("the quote must trace to a funding row, not merely change state")
                .isNotNull();
    }

    /**
     * The five-part split, as the incident produced it: every part must heal,
     * not just the ones a client happened to poll for.
     */
    @Test
    void sweepFundsEveryPartOfAFiveWaySplit() {
        Instant paidAt = Instant.now().minus(Duration.ofHours(1));
        List<String> quoteIds = List.of(
                VoucherTestSupport.newQuoteId("it-split-0"),
                VoucherTestSupport.newQuoteId("it-split-1"),
                VoucherTestSupport.newQuoteId("it-split-2"),
                VoucherTestSupport.newQuoteId("it-split-3"),
                VoucherTestSupport.newQuoteId("it-split-4"));
        quoteIds.forEach(id -> seedPaidButUnfunded(id, paidAt));

        reconciler.reconcileTick();

        for (String id : quoteIds) {
            assertThat(voucherQuoteJpaRepository.findById(id).orElseThrow().getLifecycleState())
                    .as("part %s must be funded", id)
                    .isEqualTo(VoucherLifecycleState.FUNDED);
        }
    }

    /**
     * A payment that landed seconds ago is still being handled by the webhook
     * path, so the sweep must leave it alone rather than race half A.
     */
    @Test
    void sweepLeavesAQuoteAloneWhileItIsStillInsideTheGracePeriod() {
        String quoteId = VoucherTestSupport.newQuoteId("it-fresh");
        seedPaidButUnfunded(quoteId, Instant.now());

        reconciler.reconcileTick();

        assertThat(voucherQuoteJpaRepository.findById(quoteId).orElseThrow().getLifecycleState())
                .isEqualTo(VoucherLifecycleState.UNFUNDED);
    }

    /**
     * An unpaid quote has no accepted event and must never be funded — the
     * sweep hands out value only where money actually arrived.
     */
    @Test
    void sweepIgnoresAQuoteThatWasNeverPaidFor() {
        String quoteId = VoucherTestSupport.newQuoteId("it-unpaid");
        voucherQuoteJpaRepository.save(VoucherTestSupport.unfundedQuote(quoteId, 20_000L));

        reconciler.reconcileTick();

        assertThat(voucherQuoteJpaRepository.findById(quoteId).orElseThrow().getLifecycleState())
                .isEqualTo(VoucherLifecycleState.UNFUNDED);
    }

    /**
     * The gauge behind the alert: it must read the standing liability while a
     * quote is stranded and return to zero once the sweep heals it. A gauge
     * that cannot do both is an alert that either never fires or never clears.
     */
    @Test
    void paidUnfundedGaugeCountsTheLiabilityAndClearsOnceSwept() {
        String quoteId = VoucherTestSupport.newQuoteId("it-gauge");
        seedPaidButUnfunded(quoteId, Instant.now().minus(Duration.ofHours(1)));

        assertThat(voucherQuoteJpaRepository.countPaidUnfunded())
                .as("money taken and nothing issued against it")
                .isEqualTo(1L);

        reconciler.reconcileTick();

        assertThat(voucherQuoteJpaRepository.countPaidUnfunded())
                .as("the alert must clear once the liability is resolved")
                .isZero();
    }

    /**
     * Running twice must not attach a second funding row: the CAS is what makes
     * the sweep safe to run on a schedule forever.
     */
    @Test
    void sweepIsIdempotentAcrossRepeatedRuns() {
        String quoteId = VoucherTestSupport.newQuoteId("it-twice");
        seedPaidButUnfunded(quoteId, Instant.now().minus(Duration.ofHours(1)));

        reconciler.reconcileTick();
        String fundingAfterFirst =
                voucherQuoteJpaRepository.findById(quoteId).orElseThrow().getFundingId();

        reconciler.reconcileTick();

        assertThat(voucherQuoteJpaRepository.findById(quoteId).orElseThrow().getFundingId())
                .isEqualTo(fundingAfterFirst);
        assertThat(voucherFundingJpaRepository.count()).isEqualTo(1L);
    }
}
