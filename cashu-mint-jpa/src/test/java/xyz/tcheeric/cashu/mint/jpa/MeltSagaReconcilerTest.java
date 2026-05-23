package xyz.tcheeric.cashu.mint.jpa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.mint.proto.domain.MeltSagaState;
import xyz.tcheeric.cashu.mint.proto.domain.PaymentOutcome;
import xyz.tcheeric.cashu.mint.proto.ports.LightningPaymentPort;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSaga;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSagaRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec 002 T060/T212/T213 — verifies the scheduled reconciler resolves
 * PAYMENT_UNKNOWN via checkStatus polls, fires alerts after the
 * payment-unknown TTL elapses, and sweeps stale PROOFS_HELD sagas.
 */
class MeltSagaReconcilerTest {

    private MeltSagaRepository sagaRepo;
    private LightningPaymentPort paymentPort;
    private MeltSagaReconciler reconciler;

    @BeforeEach
    void setUp() {
        sagaRepo = Mockito.mock(MeltSagaRepository.class);
        paymentPort = Mockito.mock(LightningPaymentPort.class);
        when(sagaRepo.casState(anyString(), any(), any())).thenReturn(1);
        reconciler = new MeltSagaReconciler(sagaRepo, paymentPort, /*proofVaultService*/ null,
                Duration.ofHours(1), Duration.ofMinutes(5));
    }

    @Test
    void payment_unknown_with_success_advances_to_COMPLETED() {
        MeltSaga saga = stub("saga-1", "quote-1", MeltSagaState.PAYMENT_UNKNOWN, Instant.now());
        when(sagaRepo.findByState(MeltSagaState.PAYMENT_UNKNOWN)).thenReturn(List.of(saga));
        when(sagaRepo.findByState(MeltSagaState.PROOFS_HELD)).thenReturn(List.of());
        when(paymentPort.checkStatus("quote-1"))
                .thenReturn(new PaymentOutcome.Success("pre-1", 100L, 0L, "evt-1"));

        reconciler.reconcileTick();

        verify(sagaRepo).casState("saga-1",
                MeltSagaState.PAYMENT_UNKNOWN, MeltSagaState.COMPLETED);
        verify(sagaRepo).recordTransition(eq("saga-1"),
                eq(MeltSagaState.PAYMENT_UNKNOWN), eq(MeltSagaState.COMPLETED),
                anyString(), eq("poll"));
    }

    @Test
    void payment_unknown_with_definitive_failure_advances_to_FAILED() {
        MeltSaga saga = stub("saga-2", "quote-2", MeltSagaState.PAYMENT_UNKNOWN, Instant.now());
        when(sagaRepo.findByState(MeltSagaState.PAYMENT_UNKNOWN)).thenReturn(List.of(saga));
        when(sagaRepo.findByState(MeltSagaState.PROOFS_HELD)).thenReturn(List.of());
        when(paymentPort.checkStatus("quote-2"))
                .thenReturn(new PaymentOutcome.DefinitiveFailure("no_route", "1001"));

        reconciler.reconcileTick();

        verify(sagaRepo).casState("saga-2",
                MeltSagaState.PAYMENT_UNKNOWN, MeltSagaState.FAILED);
    }

    @Test
    void payment_unknown_still_unknown_appends_poll_entry_without_advancing() {
        MeltSaga saga = stub("saga-3", "quote-3", MeltSagaState.PAYMENT_UNKNOWN, Instant.now());
        when(sagaRepo.findByState(MeltSagaState.PAYMENT_UNKNOWN)).thenReturn(List.of(saga));
        when(sagaRepo.findByState(MeltSagaState.PROOFS_HELD)).thenReturn(List.of());
        when(paymentPort.checkStatus("quote-3"))
                .thenReturn(new PaymentOutcome.Unknown("still_pending"));

        reconciler.reconcileTick();

        verify(sagaRepo, never()).casState(eq("saga-3"), any(), any());
        verify(sagaRepo).recordTransition(eq("saga-3"),
                eq(MeltSagaState.PAYMENT_UNKNOWN), eq(MeltSagaState.PAYMENT_UNKNOWN),
                anyString(), eq("poll"));
    }

    @Test
    void payment_unknown_ttl_expired_does_not_auto_advance_but_no_special_action() {
        // FR-007 / FR-008: ttl expiry fires an operator alert (via log) but
        // never advances the saga. The alert is observable only via logs in
        // v1; assert that the saga is left untouched.
        Instant created = Instant.now().minus(Duration.ofHours(2));
        MeltSaga saga = stub("saga-4", "quote-4", MeltSagaState.PAYMENT_UNKNOWN, created);
        when(sagaRepo.findByState(MeltSagaState.PAYMENT_UNKNOWN)).thenReturn(List.of(saga));
        when(sagaRepo.findByState(MeltSagaState.PROOFS_HELD)).thenReturn(List.of());
        when(paymentPort.checkStatus("quote-4"))
                .thenReturn(new PaymentOutcome.Unknown("still_pending"));

        reconciler.reconcileTick();

        verify(sagaRepo, never()).casState(eq("saga-4"), any(), any());
    }

    @Test
    void stale_proofs_held_is_swept_to_FAILED() {
        Instant stale = Instant.now().minus(Duration.ofMinutes(10));
        MeltSaga saga = stub("saga-5", "quote-5", MeltSagaState.PROOFS_HELD, stale);
        when(sagaRepo.findByState(MeltSagaState.PAYMENT_UNKNOWN)).thenReturn(List.of());
        when(sagaRepo.findByState(MeltSagaState.PROOFS_HELD)).thenReturn(List.of(saga));

        reconciler.reconcileTick();

        verify(sagaRepo).casState("saga-5",
                MeltSagaState.PROOFS_HELD, MeltSagaState.FAILED);
        verify(sagaRepo).recordTransition(eq("saga-5"),
                eq(MeltSagaState.PROOFS_HELD), eq(MeltSagaState.FAILED),
                anyString(), eq("sweep"));
    }

    @Test
    void fresh_proofs_held_is_NOT_swept() {
        MeltSaga saga = stub("saga-6", "quote-6", MeltSagaState.PROOFS_HELD, Instant.now());
        when(sagaRepo.findByState(MeltSagaState.PAYMENT_UNKNOWN)).thenReturn(List.of());
        when(sagaRepo.findByState(MeltSagaState.PROOFS_HELD)).thenReturn(List.of(saga));

        reconciler.reconcileTick();

        verify(sagaRepo, never()).casState(eq("saga-6"), any(), any());
    }

    @Test
    void reconciler_skips_quietly_when_payment_port_is_unwired() {
        MeltSagaReconciler portless = new MeltSagaReconciler(sagaRepo, null, /*proofVaultService*/ null,
                Duration.ofHours(1), Duration.ofMinutes(5));
        portless.reconcileTick();
        // No interactions with sagaRepo.findByState — the reconciler returns early.
        verify(sagaRepo, never()).findByState(any());
    }

    @Test
    void reconciler_NEVER_calls_pay() {
        // FR-007 compliance gate: the reconciler must only read provider
        // state via checkStatus, never invoke pay.
        MeltSaga saga = stub("saga-7", "quote-7", MeltSagaState.PAYMENT_UNKNOWN, Instant.now());
        when(sagaRepo.findByState(MeltSagaState.PAYMENT_UNKNOWN)).thenReturn(List.of(saga));
        when(sagaRepo.findByState(MeltSagaState.PROOFS_HELD)).thenReturn(List.of());
        when(paymentPort.checkStatus(anyString()))
                .thenReturn(new PaymentOutcome.Unknown("still"));

        reconciler.reconcileTick();

        verify(paymentPort, never()).pay(anyString(), any(Duration.class));
    }

    private static MeltSaga stub(String sagaId, String quoteId, MeltSagaState state, Instant createdAt) {
        MeltSaga saga = Mockito.mock(MeltSaga.class);
        when(saga.meltSagaId()).thenReturn(sagaId);
        when(saga.quoteId()).thenReturn(quoteId);
        when(saga.currentState()).thenReturn(state);
        when(saga.createdAt()).thenReturn(createdAt);
        return saga;
    }
}
