package xyz.tcheeric.cashu.mint.rest.spec002;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.context.annotation.Primary;
import xyz.tcheeric.cashu.mint.jpa.MeltSagaReconciler;
import xyz.tcheeric.cashu.mint.jpa.entity.MeltSagaEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.MeltSagaTransitionEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.MeltSagaJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.MeltSagaTransitionJpaRepository;
import xyz.tcheeric.cashu.mint.proto.domain.MeltSagaState;
import xyz.tcheeric.cashu.mint.proto.domain.PaymentOutcome;
import xyz.tcheeric.cashu.mint.proto.ports.LightningPaymentPort;
import xyz.tcheeric.cashu.mint.rest.spec001.AbstractMintDurableIT;
import xyz.tcheeric.cashu.mint.rest.spec002.support.MockLightningPaymentPort;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 002 — drives the production {@link MeltSagaReconciler} against the
 * Testcontainers Postgres harness with a programmable
 * {@link MockLightningPaymentPort} test double. Verifies the
 * PAYMENT_UNKNOWN → COMPLETED / FAILED recovery paths AND that the
 * PROOFS_HELD TTL sweep actually moves stale sagas to FAILED.
 *
 * <p>Maps loosely to T203 (PAYMENT_UNKNOWN end-to-end recovery) at the
 * reconciler level. The full mint-path IT for T203 — driving a real
 * /v1/melt request whose payment returns Unknown — requires a BDHKE
 * signed-proof fixture and is deferred.
 */
@Import(MeltSagaReconcilerIT.MockPaymentConfig.class)
// The reconciler this test drives is also @Scheduled every 60s by default.
// A tick that fires between seed() and the explicit reconcileTick() sweeps
// the saga first, so the transition the assertions look for is already
// there and the *next* one belongs to a different actor — an intermittent
// "expected sweep but was system" that depends only on wall-clock timing.
// Pushing the schedule out to an hour leaves reconcileTick() the sole
// driver, which is what every assertion here assumes.
@TestPropertySource(properties = {
        "cashu.mint.melt.reconcile-interval=PT1H",
        "cashu.mint.invariant.poll-interval=PT1H"
})
class MeltSagaReconcilerIT extends AbstractMintDurableIT {

    @TestConfiguration
    static class MockPaymentConfig {
        @Bean
        @Primary
        public LightningPaymentPort mockLightningPaymentPort() {
            return new MockLightningPaymentPort();
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    MeltSagaReconciler reconciler;

    @org.springframework.beans.factory.annotation.Autowired
    LightningPaymentPort paymentPort;

    @org.springframework.beans.factory.annotation.Autowired
    MeltSagaJpaRepository sagas;

    /**
     * The vault is an external service this suite does not run. A mock that
     * answers 0 by default is exactly right for these tests: refundForHold is
     * an idempotent conditional update, so "no rows matched" is a legitimate
     * success, and any test needing a failure stubs it explicitly.
     */
    @org.springframework.boot.test.mock.mockito.MockBean
    xyz.tcheeric.cashu.mint.proto.service.ProofVaultService proofVaultService;

    @org.springframework.beans.factory.annotation.Autowired
    MeltSagaTransitionJpaRepository transitions;

    private MockLightningPaymentPort mock() {
        return (MockLightningPaymentPort) paymentPort;
    }

    @BeforeEach
    void clean() {
        // deleteAllInBatch, not deleteAll.
        //
        // deleteAll() loads every entity and removes them one by one through
        // the persistence context. The reconciler writes transition rows from
        // its own transaction, so a row inserted after this test's context
        // last read the table is invisible to that load and survives the
        // delete — and then the saga delete fails:
        //
        //   update or delete on table "melt_saga" violates foreign key
        //   constraint "melt_saga_transition_saga_fk"
        //   Detail: Key (melt_saga_id)=(saga-stale-held) is still
        //           referenced from table "melt_saga_transition".
        //
        // That left the previous test's saga in place, so the next test's
        // seed() collided with it and the sweep it expected had already run.
        // It failed roughly one run in four, and the message it produced
        // pointed at the assertion rather than at the cleanup.
        //
        // deleteAllInBatch issues a single DELETE and sees every committed
        // row regardless of which transaction wrote it.
        transitions.deleteAllInBatch();
        sagas.deleteAllInBatch();
        mock().reset();
    }

    @AfterEach
    void cleanAfter() {
        clean();
    }

    @Test
    void reconciler_advances_PAYMENT_UNKNOWN_to_COMPLETED_when_provider_confirms() {
        seed(sagaId("saga-unknown-ok"), "quote-unknown-ok",
                MeltSagaState.PAYMENT_UNKNOWN, Instant.now());
        mock().enqueueCheckStatus(new PaymentOutcome.Success(
                "preimage-ok", 100L, 0L, "evt-ok"));

        reconciler.reconcileTick();

        assertThat(sagas.findById(sagaId("saga-unknown-ok")).orElseThrow().getCurrentState())
                .isEqualTo(MeltSagaState.COMPLETED);
        // Transition timeline shows the reconciler's append.
        List<MeltSagaTransitionEntity> timeline = transitions.findTimeline(sagaId("saga-unknown-ok"));
        assertThat(timeline).extracting(MeltSagaTransitionEntity::getToState)
                .contains(MeltSagaState.COMPLETED);
        assertThat(timeline.get(timeline.size() - 1).getActor()).isEqualTo("poll");
        // FR-007 compliance: the reconciler MUST NOT have called pay().
        assertThat(mock().payCallsFor("quote-unknown-ok")).isEqualTo(0);
    }

    @Test
    void reconciler_advances_PAYMENT_UNKNOWN_to_FAILED_on_definitive_failure() {
        seed(sagaId("saga-unknown-fail"), "quote-unknown-fail",
                MeltSagaState.PAYMENT_UNKNOWN, Instant.now());
        mock().enqueueCheckStatus(new PaymentOutcome.DefinitiveFailure(
                "route_not_found", "1001"));

        reconciler.reconcileTick();

        assertThat(sagas.findById(sagaId("saga-unknown-fail")).orElseThrow().getCurrentState())
                .isEqualTo(MeltSagaState.FAILED);
        assertThat(mock().payCallsFor("quote-unknown-fail")).isEqualTo(0);
    }

    @Test
    void reconciler_appends_poll_entry_when_still_unknown_no_advance() {
        seed("saga-unknown-stuck", "quote-unknown-stuck",
                MeltSagaState.PAYMENT_UNKNOWN, Instant.now());
        mock().enqueueCheckStatus(new PaymentOutcome.Unknown("still_pending"));

        reconciler.reconcileTick();

        assertThat(sagas.findById("saga-unknown-stuck").orElseThrow().getCurrentState())
                .isEqualTo(MeltSagaState.PAYMENT_UNKNOWN);
        // No state change but a poll transition was appended (operator
        // dashboards see the polling cadence).
        List<MeltSagaTransitionEntity> timeline = transitions.findTimeline("saga-unknown-stuck");
        // Seed inserts 2 transitions (null → PROOFS_HELD + PROOFS_HELD → PAYMENT_UNKNOWN);
        // the reconciler appends a poll no-op for a total of 3.
        assertThat(timeline).hasSize(3);
        MeltSagaTransitionEntity last = timeline.get(timeline.size() - 1);
        assertThat(last.getActor()).isEqualTo("poll");
        assertThat(last.getFromState()).isEqualTo(MeltSagaState.PAYMENT_UNKNOWN);
        assertThat(last.getToState()).isEqualTo(MeltSagaState.PAYMENT_UNKNOWN);
    }

    @Test
    void reconciler_sweeps_stale_PROOFS_HELD_to_FAILED() {
        // Seed an old PROOFS_HELD that exceeds the configured 5-min TTL.
        seed(sagaId("saga-stale-held"), "quote-stale-held", MeltSagaState.PROOFS_HELD,
                Instant.now().minus(Duration.ofMinutes(10)));

        reconciler.reconcileTick();

        assertThat(sagas.findById(sagaId("saga-stale-held")).orElseThrow().getCurrentState())
                .isEqualTo(MeltSagaState.FAILED);
        // Assert the transition the sweep is responsible for, not whichever
        // entry happens to be last.
        //
        // The previous version read timeline.get(size - 1) and expected actor
        // "sweep". It failed intermittently — roughly two runs in three — with
        // "expected sweep but was system", and adding an instrumentation write
        // to the same method made it pass three for three. That timing
        // sensitivity is the tell: something else can append to this saga's
        // timeline around the sweep, and asserting on position rather than on
        // content made the test depend on winning that race.
        //
        // What the sweep must guarantee is that PROOFS_HELD -> FAILED happened
        // and that the sweep is the actor who did it. That is true regardless
        // of what else lands on the timeline, so this states it directly.
        // Awaited, not read once.
        //
        // sweepStaleProofsHeld is not transactional, so casState and recordTransition commit
        // separately. This test therefore has a real window where the saga already reads FAILED (the
        // assertion above) and the sweep's transition row is not yet visible to this connection. A
        // single read lands inside that window under suite load, which is why the failure only ever
        // appeared in a full-suite run and never in isolation.
        //
        // Four structural explanations were tried and disproved before this one: the duplicate-key
        // race (#478, a real bug but not this), disabling the scheduler (made it worse), per-method
        // saga ids, and cleaning up other tests' leaked PROOFS_HELD sagas. None changed the rate,
        // which is what pointed at visibility rather than logic. See #480.
        //
        // The sibling test above already awaits for the same reason.
        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(100, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    List<MeltSagaTransitionEntity> timeline =
                            transitions.findTimeline(sagaId("saga-stale-held"));
                    assertThat(timeline)
                            .as("the TTL sweep must record its own PROOFS_HELD -> FAILED transition; "
                                    + "timeline=%s", describe(timeline))
                            .anySatisfy(entry -> {
                                assertThat(entry.getActor()).isEqualTo("sweep");
                                assertThat(entry.getFromState()).isEqualTo(MeltSagaState.PROOFS_HELD);
                                assertThat(entry.getToState()).isEqualTo(MeltSagaState.FAILED);
                            });
                });

        // Re-read after awaiting, so the terminal-state assertion below sees the same settled timeline
        // the await just confirmed rather than a snapshot taken before it.
        List<MeltSagaTransitionEntity> timeline = transitions.findTimeline(sagaId("saga-stale-held"));

        // And nothing may move it back out of FAILED afterwards: FAILED is
        // terminal, so a later transition away from it would mean the sweep's
        // work was undone.
        // Nothing may transition AWAY from FAILED. The proof_settled marker
        // (#464) is a self-transition FAILED -> FAILED recording that the
        // proofs were settled, so it is excluded: it does not move the saga,
        // and requiring its absence would forbid the audit trail this issue
        // exists to guarantee.
        assertThat(timeline)
                .as("FAILED is terminal; nothing may move the saga out of it. timeline=%s",
                        describe(timeline))
                .noneSatisfy(entry -> {
                    assertThat(entry.getFromState()).isEqualTo(MeltSagaState.FAILED);
                    assertThat(entry.getToState()).isNotEqualTo(MeltSagaState.FAILED);
                });
    }

    @Test
    void fresh_PROOFS_HELD_is_NOT_swept() {
        seed(sagaId("saga-fresh-held"), "quote-fresh-held", MeltSagaState.PROOFS_HELD,
                Instant.now());

        reconciler.reconcileTick();

        // Awaitility nudge in case @Scheduled fires in the background and
        // mutates state asynchronously; allow a brief settle window then
        // assert the saga is still PROOFS_HELD.
        Awaitility.await().atMost(2, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(
                        sagas.findById(sagaId("saga-fresh-held")).orElseThrow().getCurrentState())
                        .isEqualTo(MeltSagaState.PROOFS_HELD));
    }

    /**
     * Issue #464 — the audit row must be written before the money moves.
     *
     * <p>The reconciler used to settle proofs and then record the transition.
     * A failure between the two left proofs already moved with nothing in the
     * timeline saying why, and {@code casState} had taken the saga out of the
     * state the sweep selects on, so nothing would retry. The record was lost
     * exactly when it mattered.
     *
     * <p>Asserting on order rather than presence: both rows exist either way,
     * so a test that only checked they were there would pass against the
     * original bug.
     */
    @Test
    void theTransitionIsRecordedBeforeTheProofsAreSettled() {
        seed(sagaId("saga-order"), "quote-order", MeltSagaState.PROOFS_HELD,
                Instant.now().minus(Duration.ofMinutes(10)));

        reconciler.reconcileTick();

        List<MeltSagaTransitionEntity> timeline = transitions.findTimeline(sagaId("saga-order"));
        int terminal = indexOfReason(timeline, "proofs_held_ttl_expired");
        int settled = indexOfReason(timeline, "proof_settled");

        assertThat(terminal)
                .as("the PROOFS_HELD -> FAILED transition must be recorded. timeline=%s",
                        describe(timeline))
                .isGreaterThanOrEqualTo(0);
        assertThat(settled)
                .as("a successful settle must leave a proof_settled marker. timeline=%s",
                        describe(timeline))
                .isGreaterThanOrEqualTo(0);
        assertThat(terminal)
                .as("""
                        The audit row must come first. Both vault operations are idempotent                         conditional updates, so a record written and not acted on is                         recoverable; money moved with no record of it is not. timeline=%s""",
                        describe(timeline))
                .isLessThan(settled);
    }

    /**
     * A settle that fails must leave the saga visibly unsettled.
     *
     * <p>Nothing retries this path, so the marker's absence is the only signal
     * that a customer's proofs are stuck PENDING. {@code
     * countTerminalWithUnsettledProofs} is what the
     * {@code cashu_mint_melt_terminal_unsettled} gauge reads.
     */
    @Test
    void aFailedSettleLeavesTheSagaCountedAsUnsettled() throws Exception {
        org.mockito.Mockito.when(
                        proofVaultService.refundForHold(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("vault down"));

        seed(sagaId("saga-unsettled"), "quote-unsettled", MeltSagaState.PROOFS_HELD,
                Instant.now().minus(Duration.ofMinutes(10)));

        reconciler.reconcileTick();

        List<MeltSagaTransitionEntity> timeline = transitions.findTimeline(sagaId("saga-unsettled"));
        assertThat(indexOfReason(timeline, "proof_settled"))
                .as("a failed settle must NOT claim to have settled. timeline=%s",
                        describe(timeline))
                .isEqualTo(-1);

        assertThat(sagas.countTerminalWithUnsettledProofs(Instant.now()))
                .as("""
                        The saga is terminal, its proofs may still be PENDING, and no sweep                         will find it again because casState already moved it. The gauge is                         the whole mechanism here, so it has to count this.""")
                .isEqualTo(1L);
    }

    /** A settle that succeeds must not be counted as unsettled. */
    @Test
    void aSuccessfulSettleIsNotCountedAsUnsettled() {
        seed(sagaId("saga-settled"), "quote-settled", MeltSagaState.PROOFS_HELD,
                Instant.now().minus(Duration.ofMinutes(10)));

        reconciler.reconcileTick();

        assertThat(sagas.countTerminalWithUnsettledProofs(Instant.now()))
                .as("a gauge that counts healthy sagas is one nobody will act on")
                .isZero();
    }

    /** Index of the first transition with the given reason, or -1. */
    private static int indexOfReason(List<MeltSagaTransitionEntity> timeline, String reason) {
        for (int i = 0; i < timeline.size(); i++) {
            if (reason.equals(timeline.get(i).getReason())) {
                return i;
            }
        }
        return -1;
    }


    /**
     * A saga id unique to this JVM run.
     *
     * <p>Every melt IT shares one Postgres, and several wipe melt_saga in
     * their own @BeforeEach. With fixed ids, a sibling's cleanup could delete
     * a row this test had just seeded — the reconciler would then CAS it,
     * refund against the vault, and fail to write the transition with
     *
     *   Key (melt_saga_id)=(saga-stale-held) is not present in table "melt_saga"
     *
     * surfacing as "the TTL sweep must record its own transition", three steps
     * from the cause. Namespacing the ids removes the collision instead of
     * trying to order the classes.
     */
    private static final String RUN = java.util.UUID.randomUUID().toString().substring(0, 8);

    private static String sagaId(String name) {
        return name + "-" + RUN;
    }

    /** Renders a timeline so a failure names what actually happened. */
    private static String describe(List<MeltSagaTransitionEntity> timeline) {
        return timeline.stream()
                .map(t -> t.getSeq() + ":" + t.getActor() + ":" + t.getReason()
                        + ":" + t.getFromState() + "->" + t.getToState())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private void seed(String sagaId, String quoteId, MeltSagaState state, Instant createdAt) {
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
        // Fail here, not three assertions later.
        //
        // These ITs share one Postgres, and melt_saga has a unique constraint
        // on quote_id. If a sibling class left a row behind, this save is
        // rejected, the saga never exists, and the reconciler has nothing to
        // sweep — which surfaced as "expected sweep but was system" on an
        // assertion about the timeline, three steps from the cause. Asserting
        // the precondition turns an intermittent misleading failure into an
        // immediate accurate one.
        assertThat(sagas.findById(sagaId))
                .as("seed(%s) must actually persist; a unique-constraint collision with a "
                        + "sibling class's leftover row would otherwise surface later as a "
                        + "missing sweep", sagaId)
                .isPresent();
        // Seed a first transition so the timeline isn't empty.
        MeltSagaTransitionEntity t = new MeltSagaTransitionEntity();
        t.setMeltSagaId(sagaId);
        t.setSeq(1);
        t.setFromState(null);
        t.setToState(MeltSagaState.PROOFS_HELD);
        t.setReason("seed");
        t.setActor("system");
        t.setAt(createdAt);
        transitions.save(t);
        if (state != MeltSagaState.PROOFS_HELD) {
            MeltSagaTransitionEntity t2 = new MeltSagaTransitionEntity();
            t2.setMeltSagaId(sagaId);
            t2.setSeq(2);
            t2.setFromState(MeltSagaState.PROOFS_HELD);
            t2.setToState(state);
            t2.setReason("seed advance");
            t2.setActor("system");
            t2.setAt(createdAt);
            transitions.save(t2);
        }
    }
}
