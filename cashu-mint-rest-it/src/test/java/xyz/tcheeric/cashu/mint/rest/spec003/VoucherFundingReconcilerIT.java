package xyz.tcheeric.cashu.mint.rest.spec003;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import xyz.tcheeric.cashu.mint.jpa.VoucherFundingReconciler;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuoteEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.WebhookEventEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.WebhookEventJpaRepository;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.WebhookEvent;
import xyz.tcheeric.cashu.mint.rest.spec003.support.AbstractVoucherDurableIT;
import xyz.tcheeric.cashu.mint.rest.spec003.support.VoucherTestSupport;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;

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

    /**
     * Comfortably past the reconciler's 2m grace period, so a row excluded from
     * the sweep is excluded on its outcome and not merely because it is young.
     */
    private static final Duration GRACE_MARGIN = Duration.ofMinutes(30);

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
     * Only {@code accepted} means money arrived. The other ten outcomes are
     * rejections and forensic records — {@code tamper} and
     * {@code amount_mismatch} in particular describe a payment the mint
     * refused — so funding a voucher off one of those would issue value
     * against a payment that never happened.
     *
     * <p>Worth an explicit test because the sweep's predicate is a native-SQL
     * string literal (<code>e.outcome = 'accepted'</code>): nothing in the type
     * system connects it to the enum, so a renamed constant or a widened
     * predicate would fail silently and in the expensive direction.
     *
     * <p><strong>Two independent guards enforce this</strong>, and the test
     * asserts both, because asserting only the outcome cannot tell them apart:
     * deleting the {@code accepted} clause from the sweep query leaves this
     * test green, since {@code VoucherFundingResolverImpl} filters on
     * {@code accepted} again when it looks for the event to build funding from.
     * That redundancy is a good thing — it is why a widened sweep cannot issue
     * value — but a test that cannot see the difference would let the first
     * guard rot unnoticed. So the sweep query is asserted directly as well.
     */
    @Test
    void sweepIgnoresQuotesWhoseOnlyPaymentEventWasRejected() {
        int rejectedOutcomes = 0;
        for (WebhookEvent.Outcome outcome : WebhookEvent.Outcome.values()) {
            if (outcome == WebhookEvent.Outcome.accepted) {
                continue;
            }
            rejectedOutcomes++;
            // newQuoteId appends a UUID; quote_id is varchar(64), so keep the prefix short.
            String quoteId = VoucherTestSupport.newQuoteId("rej");
            voucherQuoteJpaRepository.save(VoucherTestSupport.unfundedQuote(quoteId, 20_000L));

            WebhookEventEntity event = VoucherTestSupport.acceptedWebhookEvent(
                    PROVIDER, "evt-" + quoteId, quoteId, 20_000L);
            event.setOutcome(outcome);
            event.setReceivedAt(Instant.now().minus(Duration.ofHours(1)));
            webhookEvents.save(event);
        }
        assertThat(rejectedOutcomes)
                .as("every non-accepted outcome must be covered; a new enum constant "
                        + "should force this test to be revisited")
                .isEqualTo(WebhookEvent.Outcome.values().length - 1);

        // Guard 1: the sweep query itself must not even select these rows.
        assertThat(voucherQuoteJpaRepository.findPaidButUnfunded(
                Instant.now().minus(GRACE_MARGIN), 200))
                .as("the sweep query must filter on outcome='accepted'; a widened "
                        + "predicate is masked by the resolver and would go unnoticed")
                .isEmpty();

        // Guard 2: and the end-to-end sweep must leave them alone.
        reconciler.reconcileTick();

        assertThat(voucherQuoteJpaRepository.findAll())
                .as("a refused payment must never fund a voucher")
                .allSatisfy(quote -> assertThat(quote.getLifecycleState())
                        .isEqualTo(VoucherLifecycleState.UNFUNDED));
        assertThat(voucherQuoteJpaRepository.countPaidUnfunded())
                .as("the gauge counts money taken, so a refused payment must not inflate it")
                .isZero();
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

    /**
     * Two replicas sweeping at once must not create two funding rows for one
     * payment. The class javadoc claims this holds by construction — the lazy
     * insert is keyed on {@code (provider, provider_event_id)} and the attach is
     * a CAS — so the claim is worth executing rather than trusting, because the
     * failure it guards against is the mint backing one payment twice.
     */
    @Test
    void concurrentSweepsAttachExactlyOneFundingRow() throws Exception {
        String quoteId = VoucherTestSupport.newQuoteId("it-concurrent");
        seedPaidButUnfunded(quoteId, Instant.now().minus(Duration.ofHours(1)));

        int replicas = 4;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(replicas);
        var errors = new ConcurrentLinkedQueue<Throwable>();
        try (var pool = Executors.newFixedThreadPool(replicas)) {
            for (int i = 0; i < replicas; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        reconciler.reconcileTick();
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS))
                    .as("sweeps should finish promptly")
                    .isTrue();
        }

        assertThat(errors).as("a concurrent sweep must not throw").isEmpty();
        assertThat(voucherQuoteJpaRepository.findById(quoteId).orElseThrow().getLifecycleState())
                .isEqualTo(VoucherLifecycleState.FUNDED);
        assertThat(voucherFundingJpaRepository.count())
                .as("one payment must back exactly one funding row, whatever the replica count")
                .isEqualTo(1L);
    }
}
