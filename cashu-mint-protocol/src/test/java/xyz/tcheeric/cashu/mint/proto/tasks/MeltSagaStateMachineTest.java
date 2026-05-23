package xyz.tcheeric.cashu.mint.proto.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltRequest;
import xyz.tcheeric.cashu.mint.proto.domain.MeltSagaState;
import xyz.tcheeric.cashu.mint.proto.domain.PaymentOutcome;
import xyz.tcheeric.cashu.mint.proto.ports.LightningPaymentPort;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSaga;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSagaRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec 002 T206 — saga state-machine driver in {@link MeltTask}. Covers
 * every legal {@link PaymentOutcome} branch (Success → COMPLETED,
 * DefinitiveFailure → FAILED, Unknown → PAYMENT_UNKNOWN) plus the
 * burn-failure compensation path (PAYMENT_SENT → PAYMENT_SENT_BURN_FAILED).
 *
 * <p>These are pure-mock tests; the IT counterparts (T200-T205) belong in
 * cashu-mint-rest-it but depend on the cross-repo cashu-vault FK column
 * and are deferred.
 */
class MeltSagaStateMachineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void happy_path_drives_proofs_held_payment_sent_completed() throws CashuErrorException {
        Fixture f = new Fixture();
        f.gatewayReturns(invoice -> 100, /*feeReserve*/ 5);
        f.paymentReturns(new PaymentOutcome.Success("preimage-x", 100L, 0L, "preimage-x"));

        MeltTask task = f.task(/*proofSum*/ 105L);
        task.execute();

        verify(f.sagaRepo, times(1)).save(any(MeltSaga.class));
        ArgumentCaptor<MeltSagaState> from = ArgumentCaptor.forClass(MeltSagaState.class);
        ArgumentCaptor<MeltSagaState> to = ArgumentCaptor.forClass(MeltSagaState.class);
        verify(f.sagaRepo, times(3)).recordTransition(anyString(), from.capture(), to.capture(), any(), anyString());
        assertThat(to.getAllValues())
                .as("happy-path transitions")
                .containsExactly(MeltSagaState.PROOFS_HELD, MeltSagaState.PAYMENT_SENT, MeltSagaState.COMPLETED);
        // SC-003: PROOFS_HELD precedes PAYMENT_SENT — verified by the
        // ordering of recordTransition calls above. The proof PENDING
        // commit must happen BEFORE gateway pay; we verify by interaction
        // ordering on the proof port + payment port:
        org.mockito.InOrder order = Mockito.inOrder(f.proofVaultService, f.paymentPort);
        order.verify(f.proofVaultService, times(2)).storePending(any());
        order.verify(f.paymentPort).pay(anyString(), any(Duration.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void definitive_failure_lands_in_FAILED_without_invalidate() throws CashuErrorException {
        Fixture f = new Fixture();
        f.gatewayReturns(invoice -> 100, /*feeReserve*/ 5);
        f.paymentReturns(new PaymentOutcome.DefinitiveFailure("route_not_found", "1001"));

        MeltTask task = f.task(/*proofSum*/ 105L);
        assertThatThrownBy(task::execute)
                .isInstanceOf(CashuErrorException.class)
                .matches(ex -> errorCode((CashuErrorException) ex).equals("melt_invoice_not_paid_error"));

        ArgumentCaptor<MeltSagaState> to = ArgumentCaptor.forClass(MeltSagaState.class);
        verify(f.sagaRepo, times(2)).recordTransition(anyString(), any(), to.capture(), any(), anyString());
        assertThat(to.getAllValues()).containsExactly(MeltSagaState.PROOFS_HELD, MeltSagaState.FAILED);
        // The test stub of InvalidateProofsTask increments the flag; on the
        // failure branch the task short-circuits before invalidate runs.
        assertThat(f.invalidateCalled).as("invalidate must not run on FAILED").isFalse();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void unknown_outcome_parks_saga_in_PAYMENT_UNKNOWN() throws CashuErrorException {
        Fixture f = new Fixture();
        f.gatewayReturns(invoice -> 100, /*feeReserve*/ 5);
        f.paymentReturns(new PaymentOutcome.Unknown("status_not_paid_after_pay"));

        MeltTask task = f.task(/*proofSum*/ 105L);
        assertThatThrownBy(task::execute)
                .isInstanceOf(CashuErrorException.class)
                .matches(ex -> errorCode((CashuErrorException) ex).equals("payment_unknown"));

        verify(f.sagaRepo).casState(anyString(),
                eq(MeltSagaState.PROOFS_HELD), eq(MeltSagaState.PAYMENT_UNKNOWN));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void duplicate_quote_non_terminal_is_rejected_with_melt_in_progress() throws CashuErrorException {
        Fixture f = new Fixture();
        f.gatewayReturns(invoice -> 100, /*feeReserve*/ 5);
        MeltSaga existing = Mockito.mock(MeltSaga.class);
        when(existing.currentState()).thenReturn(MeltSagaState.PROOFS_HELD);
        when(existing.meltSagaId()).thenReturn("saga-existing");
        when(f.sagaRepo.findByQuoteId(anyString())).thenReturn(Optional.of(existing));

        MeltTask task = f.task(/*proofSum*/ 105L);
        assertThatThrownBy(task::execute)
                .isInstanceOf(CashuErrorException.class)
                .matches(ex -> errorCode((CashuErrorException) ex).equals("melt_in_progress"));

        verify(f.sagaRepo, never()).save(any(MeltSaga.class));
        verify(f.paymentPort, never()).pay(anyString(), any(Duration.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void burn_failure_after_payment_lands_in_PAYMENT_SENT_BURN_FAILED() throws CashuErrorException {
        Fixture f = new Fixture();
        f.gatewayReturns(invoice -> 100, /*feeReserve*/ 5);
        f.paymentReturns(new PaymentOutcome.Success("preimage-x", 100L, 0L, "preimage-x"));
        // Inject the failure on the final invalidate step: the test stub of
        // InvalidateProofsTask runs the Fixture's onInvalidate callback.
        f.onInvalidate = () -> { throw new RuntimeException("vault_unreachable"); };

        MeltTask task = f.task(/*proofSum*/ 105L);
        assertThatThrownBy(task::execute)
                .isInstanceOf(Exception.class);

        verify(f.sagaRepo).casState(anyString(),
                eq(MeltSagaState.PAYMENT_SENT), eq(MeltSagaState.PAYMENT_SENT_BURN_FAILED));
    }

    private static String errorCode(CashuErrorException ex) {
        try {
            return MAPPER.readValue(ex.getMessage(), ErrorResponse.class).code();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final class Fixture {
        final MeltSagaRepository sagaRepo = Mockito.mock(MeltSagaRepository.class);
        final LightningPaymentPort paymentPort = Mockito.mock(LightningPaymentPort.class);
        final Gateway gateway = Mockito.mock(Gateway.class);
        final MintProtocolService protocolService = Mockito.mock(MintProtocolService.class);
        final MintLoadService loadService = Mockito.mock(MintLoadService.class);
        final MintVaultService vaultService = Mockito.mock(MintVaultService.class);
        final ProofVaultService proofVaultService = Mockito.mock(ProofVaultService.class);
        final Mint mint = new Mint(UUID.randomUUID().toString());
        final KeySet keyset = KeySet.builder().id("ks-1").unit("sat").build();

        Fixture() {
            when(sagaRepo.casState(anyString(), any(), any())).thenReturn(1);
            when(sagaRepo.findByQuoteId(anyString())).thenReturn(Optional.empty());
            mint.addKeySet(keyset);
            try {
                when(loadService.keySet(anyString())).thenReturn(keyset);
                when(loadService.load(any(UUID.class), Mockito.anyBoolean())).thenReturn(mint);
                when(loadService.load(Mockito.anyBoolean())).thenReturn(List.of(mint));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            when(protocolService.createGateway(any(PaymentMethod.class))).thenReturn(gateway);
            when(protocolService.createGateway(any(PaymentMethod.class), anyString())).thenReturn(gateway);
            // verify() returns true regardless — these tests don't exercise BDHKE.
            try {
                when(protocolService.getPrivateKey(anyString(), Mockito.anyInt(), any(Mint.class)))
                        .thenReturn(null);
            } catch (Exception ignored) { }
            when(gateway.getName()).thenReturn("test-gateway");
        }

        void gatewayReturns(java.util.function.IntFunction<Integer> amountFn, int feeReserve) {
            when(gateway.getAmount(anyString())).thenReturn(amountFn.apply(0));
            when(gateway.getRequest(anyString())).thenReturn("lnbc...");
            when(gateway.getFeeReserve(anyString())).thenReturn(feeReserve);
        }

        void paymentReturns(PaymentOutcome outcome) {
            when(paymentPort.pay(anyString(), any(Duration.class))).thenReturn(outcome);
        }

        MeltTask task(long proofSum) {
            // Two proofs split across the requested sum (e.g. 100 + 5 = 105).
            long high = proofSum - 5;
            Proof p1 = stubProof((int) high);
            Proof p2 = stubProof(5);
            PostMeltRequest request = Mockito.mock(PostMeltRequest.class);
            when(request.getQuoteId()).thenReturn("quote-melt");
            when(request.getInputs()).thenReturn(List.of(p1, p2));
            when(request.getFees(any(KeySet.class))).thenReturn(0);

            // Test subclass that bypasses BDHKE verification + uses a
            // no-op InvalidateProofsTask — these tests target the saga
            // state-machine, not signature crypto or vault writes.
            return new MeltTask(request, PaymentMethod.MOCK, "sat", mint, protocolService,
                    loadService, vaultService, proofVaultService,
                    sagaRepo, paymentPort, Duration.ofSeconds(1)) {
                @Override
                public boolean verify(Proof proof) {
                    return true;
                }

                @Override
                @SuppressWarnings({"unchecked", "rawtypes"})
                protected InvalidateProofsTask createInvalidateProofsTask(List proofs) {
                    return new InvalidateProofsTask(mint, proofs, vaultService, proofVaultService) {
                        @Override
                        protected List doExecute() {
                            invalidateInvoked();
                            return java.util.Collections.emptyList();
                        }
                    };
                }
            };
        }

        // Hook so tests can verify invalidate was reached without depending
        // on the real implementation.
        boolean invalidateCalled;
        Runnable onInvalidate;
        void invalidateInvoked() {
            invalidateCalled = true;
            if (onInvalidate != null) onInvalidate.run();
        }

        private static Proof stubProof(int amount) {
            Proof p = Mockito.mock(Proof.class);
            when(p.getAmount()).thenReturn(amount);
            when(p.getKeySetId()).thenReturn("ks-1");
            Secret s = Mockito.mock(Secret.class);
            when(s.toString()).thenReturn("secret-" + UUID.randomUUID());
            when(p.getSecret()).thenReturn(s);
            xyz.tcheeric.cashu.common.Signature sig =
                    Mockito.mock(xyz.tcheeric.cashu.common.Signature.class);
            byte[] bytes = new byte[33];
            when(sig.getBytes()).thenReturn(bytes);
            when(p.getUnblindedSignature()).thenReturn(sig);
            return p;
        }
    }
}
