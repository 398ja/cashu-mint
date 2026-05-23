package xyz.tcheeric.cashu.mint.rest.spec002;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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

    @org.springframework.beans.factory.annotation.Autowired
    MeltSagaTransitionJpaRepository transitions;

    private MockLightningPaymentPort mock() {
        return (MockLightningPaymentPort) paymentPort;
    }

    @BeforeEach
    void clean() {
        transitions.deleteAll();
        sagas.deleteAll();
        mock().reset();
    }

    @AfterEach
    void cleanAfter() {
        clean();
    }

    @Test
    void reconciler_advances_PAYMENT_UNKNOWN_to_COMPLETED_when_provider_confirms() {
        seed("saga-unknown-ok", "quote-unknown-ok",
                MeltSagaState.PAYMENT_UNKNOWN, Instant.now());
        mock().enqueueCheckStatus(new PaymentOutcome.Success(
                "preimage-ok", 100L, 0L, "evt-ok"));

        reconciler.reconcileTick();

        assertThat(sagas.findById("saga-unknown-ok").orElseThrow().getCurrentState())
                .isEqualTo(MeltSagaState.COMPLETED);
        // Transition timeline shows the reconciler's append.
        List<MeltSagaTransitionEntity> timeline = transitions.findTimeline("saga-unknown-ok");
        assertThat(timeline).extracting(MeltSagaTransitionEntity::getToState)
                .contains(MeltSagaState.COMPLETED);
        assertThat(timeline.get(timeline.size() - 1).getActor()).isEqualTo("poll");
        // FR-007 compliance: the reconciler MUST NOT have called pay().
        assertThat(mock().payCallsFor("quote-unknown-ok")).isEqualTo(0);
    }

    @Test
    void reconciler_advances_PAYMENT_UNKNOWN_to_FAILED_on_definitive_failure() {
        seed("saga-unknown-fail", "quote-unknown-fail",
                MeltSagaState.PAYMENT_UNKNOWN, Instant.now());
        mock().enqueueCheckStatus(new PaymentOutcome.DefinitiveFailure(
                "route_not_found", "1001"));

        reconciler.reconcileTick();

        assertThat(sagas.findById("saga-unknown-fail").orElseThrow().getCurrentState())
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
        seed("saga-stale-held", "quote-stale-held", MeltSagaState.PROOFS_HELD,
                Instant.now().minus(Duration.ofMinutes(10)));

        reconciler.reconcileTick();

        assertThat(sagas.findById("saga-stale-held").orElseThrow().getCurrentState())
                .isEqualTo(MeltSagaState.FAILED);
        List<MeltSagaTransitionEntity> timeline = transitions.findTimeline("saga-stale-held");
        MeltSagaTransitionEntity last = timeline.get(timeline.size() - 1);
        assertThat(last.getActor()).isEqualTo("sweep");
        assertThat(last.getFromState()).isEqualTo(MeltSagaState.PROOFS_HELD);
        assertThat(last.getToState()).isEqualTo(MeltSagaState.FAILED);
    }

    @Test
    void fresh_PROOFS_HELD_is_NOT_swept() {
        seed("saga-fresh-held", "quote-fresh-held", MeltSagaState.PROOFS_HELD,
                Instant.now());

        reconciler.reconcileTick();

        // Awaitility nudge in case @Scheduled fires in the background and
        // mutates state asynchronously; allow a brief settle window then
        // assert the saga is still PROOFS_HELD.
        Awaitility.await().atMost(2, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(
                        sagas.findById("saga-fresh-held").orElseThrow().getCurrentState())
                        .isEqualTo(MeltSagaState.PROOFS_HELD));
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
