package xyz.tcheeric.cashu.mint.rest.spec002;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.cashu.mint.jpa.InvariantGaugePoller;
import xyz.tcheeric.cashu.mint.jpa.entity.MeltSagaEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.MeltSagaTransitionEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.MeltSagaJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.MeltSagaTransitionJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherQuoteJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.entity.MintQuoteEntity;
import xyz.tcheeric.cashu.mint.proto.domain.MeltSagaState;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote;
import xyz.tcheeric.cashu.mint.proto.ports.WebhookEvent;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuoteEntity;
import xyz.tcheeric.cashu.mint.rest.spec001.AbstractMintDurableIT;
import xyz.tcheeric.cashu.mint.rest.spec003.support.VoucherTestSupport;

import javax.sql.DataSource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import xyz.tcheeric.cashu.mint.rest.support.PrometheusScrape;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #344 / ADR 0002 — proves the Stuck Payment invariant reaches
 * Prometheus: seed melt sagas into the SC-004 stuck state, run the poller,
 * and read the gauge back off the real {@code /actuator/prometheus} scrape
 * endpoint rather than off the in-process registry.
 *
 * <p>Extended by issue #345 to cover the other two money-losing invariants:
 * {@code PAYMENT_SENT_BURN_FAILED} sagas and orphan voucher issuances.
 *
 * <p>{@code cashu.mint.melt.reconcile-interval} is pushed out of the way so
 * the background {@link xyz.tcheeric.cashu.mint.jpa.MeltSagaReconciler} can't
 * append a {@code poll} transition mid-test — a fresh poll row is exactly what
 * the SC-004 query treats as "not stuck", which would make this test flake.
 */
@TestPropertySource(properties = {
        "CASHU_MINT_MANAGEMENT_PORT=0",
        "cashu.observability.enabled=true",
        "management.prometheus.metrics.export.enabled=true",
        "management.endpoints.web.exposure.include=health,info,prometheus,metrics",
        "cashu.mint.melt.reconcile-interval=PT1H",
        "cashu.mint.invariant.poll-interval=PT1H"
})
class StuckPaymentInvariantGaugeIT extends AbstractMintDurableIT {

    @Value("${local.management.port}")
    int managementPort;

    @Autowired
    InvariantGaugePoller poller;

    /** Only for backdating updated_at; @PrePersist would otherwise stamp it now. */
    @Autowired
    @Qualifier("mintJpaDataSource")
    DataSource mintJpaDataSource;

    @Autowired
    MeltSagaJpaRepository sagas;

    @Autowired
    MeltSagaTransitionJpaRepository transitions;

    @Autowired
    VoucherQuoteJpaRepository voucherQuotes;

    private final RestTemplate restTemplate = new RestTemplate();

    @BeforeEach
    @AfterEach
    void clean() {
        transitions.deleteAll();
        sagas.deleteAll();
        voucherQuotes.deleteAll();
        // webhook_event outlives voucher_quote and is keyed by quote_id, not
        // by FK, so events left behind would re-attach to a later test's
        // quote with the same id and silently change what the gauges count.
        webhookEventJpaRepository.deleteAll();
        // Also @AfterEach: the base class only wipes mint_quote before each
        // test, so a stranded row seeded here would otherwise be visible to
        // whichever class runs next in the same context.
        mintQuoteJpaRepository.deleteAll();
    }

    /** The gauge must be scrapeable before the invariant ever breaks. */
    @Test
    void gaugeIsRegisteredAndZeroWhenNothingIsStuck() {
        poller.pollTick();

        String scrape = scrape();
        assertThat(scrape).containsPattern("# TYPE cashu_mint_melt_stuck_payment_unknown gauge");
        assertThat(gaugeValue(scrape, "cashu_mint_melt_stuck_payment_unknown")).isEqualTo(0.0);
    }

    /** Two sagas past TTL with no recent poll → gauge reads 2 on the scrape. */
    @Test
    void stuckSagasAreCountedAndExportedThroughTheScrapeEndpoint() {
        seedPaymentUnknown("saga-stuck-1", "quote-stuck-1", Instant.now().minus(Duration.ofHours(2)));
        seedPaymentUnknown("saga-stuck-2", "quote-stuck-2", Instant.now().minus(Duration.ofHours(3)));

        poller.pollTick();

        assertThat(gaugeValue(scrape(), "cashu_mint_melt_stuck_payment_unknown")).isEqualTo(2.0);
    }

    /** A saga younger than the TTL has not yet earned a page. */
    @Test
    void freshSagaIsNotCounted() {
        seedPaymentUnknown("saga-fresh", "quote-fresh", Instant.now());

        poller.pollTick();

        assertThat(gaugeValue(scrape(), "cashu_mint_melt_stuck_payment_unknown")).isEqualTo(0.0);
    }

    /**
     * The reconciler appends an {@code actor='poll'} transition on every tick
     * for exactly the sagas that are stuck, so a recent poll row must NOT
     * suppress the count — otherwise the alert could only ever fire once the
     * reconciler itself died. This is the deliberate divergence from the
     * SC-004 {@code NOT EXISTS} clause documented on the repository.
     */
    @Test
    void recentReconcilerPollDoesNotSuppressTheCount() {
        String polledId = "saga-polled";
        seedPaymentUnknown(polledId, "quote-polled", Instant.now().minus(Duration.ofHours(2)));
        appendTransition(polledId, 3, MeltSagaState.PAYMENT_UNKNOWN, "poll: still unknown",
                "poll", Instant.now());

        poller.pollTick();

        assertThat(gaugeValue(scrape(), "cashu_mint_melt_stuck_payment_unknown")).isEqualTo(1.0);
    }

    /** A saga the reconciler resolved out of PAYMENT_UNKNOWN is not stuck. */
    @Test
    void resolvedSagaIsNotCounted() {
        seedPaymentUnknown("saga-resolved", "quote-resolved", Instant.now().minus(Duration.ofHours(2)));
        sagas.casState("saga-resolved", MeltSagaState.PAYMENT_UNKNOWN, MeltSagaState.COMPLETED);

        poller.pollTick();

        assertThat(gaugeValue(scrape(), "cashu_mint_melt_stuck_payment_unknown")).isEqualTo(0.0);
    }

    /**
     * Issue #345 — a saga whose payment settled while its proofs stayed
     * spendable. Direct loss, so the gauge must move on the first row.
     */
    @Test
    void burnFailureSagasAreCountedAndExported() {
        seedSaga("saga-burn-failed", "quote-burn-failed",
                MeltSagaState.PAYMENT_SENT_BURN_FAILED, Instant.now());

        poller.pollTick();

        assertThat(gaugeValue(scrape(), "cashu_mint_melt_payment_sent_burn_failed")).isEqualTo(1.0);
    }

    /**
     * A saga still in flight must not read as a burn failure — this is the
     * distinction an on-call reader has to be able to make between "the
     * payment is ambiguous" and "the money is already gone".
     */
    @Test
    void inFlightSagaIsNotABurnFailure() {
        seedSaga("saga-in-flight", "quote-in-flight", MeltSagaState.PAYMENT_SENT, Instant.now());

        poller.pollTick();

        assertThat(gaugeValue(scrape(), "cashu_mint_melt_payment_sent_burn_failed")).isEqualTo(0.0);
    }

    /**
     * An operator who has settled the payment out of band marks the saga
     * resolved; {@code markResolved} appends a transition without touching
     * {@code current_state} (FR-007 / FR-011), so if the gauge keyed on state
     * alone the page could never be cleared by anything the mint exposes.
     */
    @Test
    void operatorAcknowledgementClearsTheStuckPaymentGauge() {
        seedPaymentUnknown("saga-ack", "quote-ack", Instant.now().minus(Duration.ofHours(2)));
        appendTransition("saga-ack", 3, MeltSagaState.PAYMENT_UNKNOWN,
                "marked resolved by operator", "operator:alice", Instant.now());

        poller.pollTick();

        assertThat(gaugeValue(scrape(), "cashu_mint_melt_stuck_payment_unknown")).isEqualTo(0.0);
    }

    /** Same contract for the burn-failure gauge, which nothing ever transitions out of. */
    @Test
    void operatorAcknowledgementClearsTheBurnFailureGauge() {
        seedSaga("saga-burn-ack", "quote-burn-ack",
                MeltSagaState.PAYMENT_SENT_BURN_FAILED, Instant.now());
        appendTransition("saga-burn-ack", 3, MeltSagaState.PAYMENT_SENT_BURN_FAILED,
                "reviewed", "operator:bob", Instant.now());

        poller.pollTick();

        assertThat(gaugeValue(scrape(), "cashu_mint_melt_payment_sent_burn_failed")).isEqualTo(0.0);
    }

    /**
     * The clock runs from when the saga entered PAYMENT_UNKNOWN, not from when
     * it was created: an old saga that only just turned ambiguous has not been
     * stuck for hours.
     */
    @Test
    void oldSagaThatOnlyJustBecameUnknownIsNotYetStuck() {
        seedSaga("saga-late-unknown", "quote-late-unknown",
                MeltSagaState.PAYMENT_UNKNOWN, Instant.now().minus(Duration.ofHours(3)));
        // Re-stamp the transition INTO PAYMENT_UNKNOWN as having just happened.
        transitions.deleteAll();
        appendTransition("saga-late-unknown", 1, null, MeltSagaState.PROOFS_HELD, "seed", "system",
                Instant.now().minus(Duration.ofHours(3)));
        appendTransition("saga-late-unknown", 2, MeltSagaState.PROOFS_HELD, MeltSagaState.PAYMENT_UNKNOWN,
                "just now", "system", Instant.now());

        poller.pollTick();

        assertThat(gaugeValue(scrape(), "cashu_mint_melt_stuck_payment_unknown")).isEqualTo(0.0);
    }

    /** Issue #345 — an issued voucher quote with no funding row is orphaned value. */
    @Test
    void orphanIssuanceIsCountedAndExported() {
        VoucherQuoteEntity orphan = VoucherTestSupport.unfundedQuote("orphan-quote", 1000L);
        orphan.setLifecycleState(VoucherLifecycleState.ISSUED);
        voucherQuotes.save(orphan);

        poller.pollTick();

        assertThat(gaugeValue(scrape(), "cashu_mint_voucher_orphan_issuance")).isEqualTo(1.0);
    }

    /** An unfunded quote that was never issued is not an orphan issuance. */
    @Test
    void unissuedQuoteWithoutFundingIsNotAnOrphan() {
        voucherQuotes.save(VoucherTestSupport.unfundedQuote("pending-quote", 1000L));

        poller.pollTick();

        assertThat(gaugeValue(scrape(), "cashu_mint_voucher_orphan_issuance")).isEqualTo(0.0);
    }

    /**
     * Issue #459 — the paid-unfunded gauge must carry its count out to the
     * scrape, not merely be registered.
     *
     * <p>Same blind spot as {@link #paidUnissuedGaugeExportsTheStrandedCount}:
     * the reconciler ITs prove the query, and the metric ITs prove the series
     * exists, but nothing observed the value actually reaching the exposition.
     * Deleting the {@code paid_unfunded} line from
     * {@link InvariantGaugePoller#pollTick} left every other test green.
     */
    @Test
    void paidUnfundedGaugeExportsTheStrandedCount() {
        VoucherQuoteEntity stranded = VoucherTestSupport.unfundedQuote("paid-unfunded-exported", 20_000L);
        voucherQuotes.save(stranded);
        webhookEventJpaRepository.save(VoucherTestSupport.acceptedWebhookEvent(
                "phoenixd-it", "evt-paid-unfunded-exported", "paid-unfunded-exported", 20_000L));

        poller.pollTick();

        assertThat(gaugeValue(scrape(), "cashu_mint_voucher_paid_unfunded"))
                .as("a paid-but-unfunded quote must reach the scrape, or the alert never fires")
                .isEqualTo(1.0);
    }

    /**
     * Issues #459 and #462 — the gap between the two gauges, which is where
     * staging's real discrepancy lived.
     *
     * <p>On 2026-09-20 staging held 68 {@code UNFUNDED} voucher quotes. The
     * shipped {@code countPaidUnfunded} reported 62, because 6 had no
     * {@code webhook_event} at all — yet all 6 were {@code PAID} at the payment
     * adapter. Paid-Unfunded is right to require an accepted webhook, and the
     * reconciler is right to skip rows without one, so those 6 were invisible
     * to every signal the mint had while still being money taken.
     *
     * <p>This asserts both gauges from one fixture, because the invariant that
     * matters is the <em>relationship</em>: a quote with no payment event must
     * raise the new gauge and must not raise Paid-Unfunded. Asserting either
     * alone would pass with the two queries accidentally identical.
     *
     * <p>The counts are deliberately asymmetric — 1 without a webhook, 2 with.
     * An earlier version of this test used one of each, and inverting the
     * {@code NOT EXISTS} in {@code countUnfundedWithoutWebhook} to
     * {@code EXISTS} left it green, because both queries then returned 1. Equal
     * fixture sizes cannot distinguish a query from its own negation.
     */
    @Test
    void aPaymentTheMintWasNeverToldAboutRaisesOnlyTheWithoutWebhookGauge() {
        voucherQuotes.save(VoucherTestSupport.unfundedQuote("never-notified", 30L));

        voucherQuotes.save(VoucherTestSupport.unfundedQuote("webhook-arrived", 20_000L));
        webhookEventJpaRepository.save(VoucherTestSupport.acceptedWebhookEvent(
                "phoenixd-it", "evt-webhook-arrived", "webhook-arrived", 20_000L));

        voucherQuotes.save(VoucherTestSupport.unfundedQuote("webhook-arrived-2", 10_000L));
        webhookEventJpaRepository.save(VoucherTestSupport.acceptedWebhookEvent(
                "phoenixd-it", "evt-webhook-arrived-2", "webhook-arrived-2", 10_000L));

        poller.pollTick();
        String scrape = scrape();

        assertThat(gaugeValue(scrape, "cashu_mint_voucher_unfunded_without_webhook"))
                .as("only the quote with no payment event: the one the sweep can never recover")
                .isEqualTo(1.0);
        assertThat(gaugeValue(scrape, "cashu_mint_voucher_paid_unfunded"))
                .as("the two with accepted webhooks, and not the one the mint never heard about")
                .isEqualTo(2.0);
    }

    /**
     * The third bucket, which my first version of these gauges missed
     * entirely (#459, #462).
     *
     * <p>{@code outcome} has eleven values, not two. Partitioning on
     * "has an accepted webhook" versus "has no webhook row" leaves a quote
     * whose only events were rejected — {@code amount_mismatch},
     * {@code tamper}, {@code expired} — counted by <em>neither</em> gauge:
     * Paid-Unfunded requires {@code accepted}, and Unfunded-Without-Webhook
     * requires the absence of any row.
     *
     * <p>Staging happens to have only ever recorded {@code accepted} (130 of
     * 130), so today the bucket is empty and no dashboard would reveal the
     * hole. That is luck, not design: {@code amount_mismatch} means the mint
     * saw money arrive for a known quote and refused it, which is exactly when
     * someone needs to know.
     *
     * <p>This asserts the three buckets are disjoint and that their sum is the
     * whole {@code UNFUNDED} population, so no future partition can silently
     * drop rows between them.
     */
    @Test
    void aQuoteWhoseOnlyWebhookWasRejectedIsStillAccountedFor() {
        voucherQuotes.save(VoucherTestSupport.unfundedQuote("rejected-only", 40L));
        webhookEventJpaRepository.save(VoucherTestSupport.webhookEvent(
                "phoenixd-it", "evt-rejected-only", "rejected-only", 40L,
                WebhookEvent.Outcome.amount_mismatch));

        voucherQuotes.save(VoucherTestSupport.unfundedQuote("no-webhook", 30L));

        voucherQuotes.save(VoucherTestSupport.unfundedQuote("accepted-webhook", 20_000L));
        webhookEventJpaRepository.save(VoucherTestSupport.acceptedWebhookEvent(
                "phoenixd-it", "evt-accepted-webhook", "accepted-webhook", 20_000L));

        long paidUnfunded = voucherQuotes.countPaidUnfunded();
        long withoutWebhook = voucherQuotes.countUnfundedWithoutWebhook();
        long rejectedOnly = voucherQuotes.countUnfundedRejectedOnly();
        long allUnfunded = voucherQuotes.count();

        assertThat(paidUnfunded).as("only the quote with an accepted webhook").isEqualTo(1);
        assertThat(withoutWebhook).as("only the quote with no webhook row at all").isEqualTo(1);
        assertThat(rejectedOnly)
                .as("the quote whose only event was rejected: seen, refused, and otherwise invisible")
                .isEqualTo(1);
        assertThat(paidUnfunded + withoutWebhook + rejectedOnly)
                .as("the three buckets must partition UNFUNDED exactly, with no row falling between them")
                .isEqualTo(allUnfunded);
    }

    /**
     * Issue #460 — a mint quote stuck in {@code PAID} is money accepted with
     * nothing issued against it, and nothing will issue it without the client
     * returning.
     *
     * <p>The series must be present reading {@code 0} before anything is
     * stranded. {@code gaugeValue} answers {@code -1} for an absent family,
     * which is what distinguishes "nothing stranded" from "the gauge is gone" —
     * and for this invariant, unlike every other one here, there is no
     * reconciler whose silence would eventually give the game away.
     */
    @Test
    void paidUnissuedGaugeIsExportedAndReadsZeroWhenNothingIsStranded() {
        poller.pollTick();

        assertThat(gaugeValue(scrape(), "cashu_mint_quote_paid_unissued"))
                .as("the alert reads this series; absent is not the same as zero")
                .isEqualTo(0.0);
    }

    /**
     * Issue #460 — and the gauge must actually carry the stranded count out to
     * the scrape.
     *
     * <p>Asserting only the healthy zero above cannot distinguish a working
     * gauge from one that is bound but never polled: both read {@code 0}
     * forever. Deleting the {@code paid_unissued} line from
     * {@link InvariantGaugePoller#pollTick} leaves that test green, which is
     * exactly the "declared but dead" failure this class exists to prevent —
     * so the value has to be observed with a stranded quote present.
     */
    @Test
    void paidUnissuedGaugeExportsTheStrandedCount() {
        MintQuoteEntity stranded = new MintQuoteEntity();
        stranded.setQuoteId("paid-unissued-exported");
        stranded.setAmount(300L);
        stranded.setUnit("sat");
        stranded.setMintUrl("https://mint.example");
        stranded.setPaymentMethod("bolt11");
        stranded.setInvoiceId("paid-unissued-exported");
        stranded.setLifecycleState(MintQuote.LifecycleState.PAID);
        stranded.setRequestHash("0".repeat(64));
        mintQuoteJpaRepository.save(stranded);
        mintQuoteJpaRepository.flush();
        // The invariant measures time in state, and @PrePersist stamps
        // updated_at with now(), so push it past the TTL at the SQL level.
        new JdbcTemplate(mintJpaDataSource).update(
                "UPDATE mint_quote SET created_at = ?, updated_at = ? WHERE quote_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(6))),
                Timestamp.from(Instant.now().minus(Duration.ofHours(6))),
                "paid-unissued-exported");

        poller.pollTick();

        assertThat(gaugeValue(scrape(), "cashu_mint_quote_paid_unissued"))
                .as("the stranded quote must reach the scrape, or the alert never fires")
                .isEqualTo(1.0);
    }

    private void seedPaymentUnknown(String sagaId, String quoteId, Instant createdAt) {
        seedSaga(sagaId, quoteId, MeltSagaState.PAYMENT_UNKNOWN, createdAt);
    }

    private void seedSaga(String sagaId, String quoteId, MeltSagaState state, Instant createdAt) {
        MeltSagaEntity s = new MeltSagaEntity();
        s.setMeltSagaId(sagaId);
        s.setQuoteId(quoteId);
        s.setInvoiceAmount(100L);
        s.setExactFeeReserve(5L);
        s.setInputAmount(105L);
        s.setProofCount(2);
        s.setProvider("mock-test");
        s.setCurrentState(state);
        s.setCreatedAt(createdAt);
        s.setUpdatedAt(createdAt);
        sagas.save(s);
        appendTransition(sagaId, 1, null, MeltSagaState.PROOFS_HELD, "seed", "system", createdAt);
        appendTransition(sagaId, 2, MeltSagaState.PROOFS_HELD, state, "seed advance", "system", createdAt);
    }

    /**
     * Appends a self-transition — a poll no-op or an operator annotation.
     * These must NOT read as an entry into the state, or every reconciler poll
     * would reset the stuck clock.
     */
    private void appendTransition(String sagaId, int seq, MeltSagaState toState,
                                  String reason, String actor, Instant at) {
        appendTransition(sagaId, seq, toState, toState, reason, actor, at);
    }

    /** Appends a transition from {@code fromState} into {@code toState}. */
    private void appendTransition(String sagaId, int seq, MeltSagaState fromState, MeltSagaState toState,
                                  String reason, String actor, Instant at) {
        MeltSagaTransitionEntity t = new MeltSagaTransitionEntity();
        t.setMeltSagaId(sagaId);
        t.setSeq(seq);
        t.setFromState(fromState);
        t.setToState(toState);
        t.setReason(reason);
        t.setActor(actor);
        t.setAt(at);
        transitions.save(t);
    }

    private String scrape() {
        return PrometheusScrape.body(restTemplate, managementPort);
    }

    /** Value of a single gauge series, or -1 when the family is absent. */
    private double gaugeValue(String scrape, String metric) {
        Matcher matcher = Pattern.compile(
                        "^" + Pattern.quote(metric) + "\\{[^}]*}\\s+([0-9.E+-]+)$",
                        Pattern.MULTILINE)
                .matcher(scrape == null ? "" : scrape);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : -1.0;
    }
}
