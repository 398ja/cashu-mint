package xyz.tcheeric.cashu.mint.jpa;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.mint.proto.domain.MeltSagaState;
import xyz.tcheeric.cashu.mint.proto.domain.PaymentOutcome;
import xyz.tcheeric.cashu.mint.proto.ports.LightningPaymentPort;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSaga;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSagaRepository;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Spec references:
 * <ul>
 *   <li><a href="https://github.com/cashubtc/nuts/blob/main/05.md">NUT-05</a> —
 *       melt tokens; the reconciler resolves sagas whose {@code Gateway.pay}
 *       outcome was ambiguous.</li>
 * </ul>
 * Spec URL is pinned to {@code main} per FR-014; commit-hash pinning is
 * tracked as a follow-up cross-repo alongside spec 001's NUT links.
 *
 * <p>Spec 002 T060 / T212 / T213 — scheduled reconciler that:
 * <ol>
 *   <li>polls {@link LightningPaymentPort#checkStatus} for every saga in
 *       {@link MeltSagaState#PAYMENT_UNKNOWN}, advancing the saga to
 *       {@code COMPLETED} or {@code FAILED} on a definitive provider
 *       response. After {@code cashu.mint.melt.payment-unknown-ttl}
 *       expires without resolution, fires an operator alert and leaves
 *       the saga in {@code PAYMENT_UNKNOWN} for manual review (FR-008);</li>
 *   <li>sweeps stale {@link MeltSagaState#PROOFS_HELD} sagas — anything
 *       older than {@code cashu.mint.melt.proofs-held-ttl} that has not
 *       advanced is moved to {@code FAILED} so the held proofs are not
 *       indefinitely stuck (research R9).</li>
 * </ol>
 *
 * <p>FR-007 compliance: the reconciler MUST NEVER call
 * {@link LightningPaymentPort#pay}. It only reads provider state via
 * {@code checkStatus}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
public class MeltSagaReconciler {

    private final MeltSagaRepository sagaRepository;
    private final LightningPaymentPort lightningPaymentPort;
    private final ProofVaultService proofVaultService;
    private final Duration paymentUnknownTtl;
    private final Duration proofsHeldTtl;

    public MeltSagaReconciler(
            MeltSagaRepository sagaRepository,
            @Autowired(required = false) LightningPaymentPort lightningPaymentPort,
            @Autowired(required = false) ProofVaultService proofVaultService,
            @Value("${cashu.mint.melt.payment-unknown-ttl:PT1H}") Duration paymentUnknownTtl,
            @Value("${cashu.mint.melt.proofs-held-ttl:PT5M}") Duration proofsHeldTtl) {
        this.sagaRepository = sagaRepository;
        this.lightningPaymentPort = lightningPaymentPort;
        this.proofVaultService = proofVaultService;
        this.paymentUnknownTtl = paymentUnknownTtl;
        this.proofsHeldTtl = proofsHeldTtl;
    }

    @Scheduled(fixedDelayString = "${cashu.mint.melt.reconcile-interval:PT60S}")
    public void reconcileTick() {
        if (lightningPaymentPort == null) {
            log.debug("melt_saga_reconcile_skipped reason=no_lightning_payment_port");
            return;
        }
        try {
            resolvePaymentUnknown();
        } catch (RuntimeException e) {
            log.warn("melt_saga_reconcile payment_unknown_pass_failed cause={}", e.getMessage());
        }
        try {
            sweepStaleProofsHeld();
        } catch (RuntimeException e) {
            log.warn("melt_saga_reconcile proofs_held_sweep_failed cause={}", e.getMessage());
        }
    }

    /**
     * Polls {@link LightningPaymentPort#checkStatus} for every PAYMENT_UNKNOWN
     * saga and applies the typed outcome. Sagas older than the configured
     * TTL fire an operator alert but are not auto-resolved (FR-007 / FR-008).
     */
    void resolvePaymentUnknown() {
        List<MeltSaga> unknowns = sagaRepository.findByState(MeltSagaState.PAYMENT_UNKNOWN);
        Instant ttlBoundary = Instant.now().minus(paymentUnknownTtl);
        for (MeltSaga saga : unknowns) {
            PaymentOutcome outcome = lightningPaymentPort.checkStatus(saga.quoteId());
            if (outcome instanceof PaymentOutcome.Success success) {
                int updated = sagaRepository.casState(
                        saga.meltSagaId(), MeltSagaState.PAYMENT_UNKNOWN, MeltSagaState.COMPLETED);
                if (updated == 1) {
                    // Spec 002 T011 + Codex review — proofs were left PENDING
                    // when the saga entered PAYMENT_UNKNOWN; on resolve they
                    // MUST advance with the saga or they'll be stuck.
                    settleProofs(saga.meltSagaId(), /*spent*/ true);
                    sagaRepository.recordTransition(saga.meltSagaId(),
                            MeltSagaState.PAYMENT_UNKNOWN, MeltSagaState.COMPLETED,
                            "reconciled preimage=" + success.paymentHash(), "poll");
                    log.info("[melt-saga] reconciled_completed saga_id={} quote_id={}",
                            saga.meltSagaId(), saga.quoteId());
                }
            } else if (outcome instanceof PaymentOutcome.DefinitiveFailure failure) {
                int updated = sagaRepository.casState(
                        saga.meltSagaId(), MeltSagaState.PAYMENT_UNKNOWN, MeltSagaState.FAILED);
                if (updated == 1) {
                    settleProofs(saga.meltSagaId(), /*spent*/ false);
                    sagaRepository.recordTransition(saga.meltSagaId(),
                            MeltSagaState.PAYMENT_UNKNOWN, MeltSagaState.FAILED,
                            "reconciled " + failure.reason() + ":" + failure.providerCode(), "poll");
                    log.info("[melt-saga] reconciled_failed saga_id={} quote_id={} reason={}",
                            saga.meltSagaId(), saga.quoteId(), failure.reason());
                }
            } else {
                // Still Unknown. Append a no-op timeline entry so operators
                // can see the polling cadence. If the saga is older than the
                // TTL, fire an alert; we do NOT auto-advance.
                sagaRepository.recordTransition(saga.meltSagaId(),
                        MeltSagaState.PAYMENT_UNKNOWN, MeltSagaState.PAYMENT_UNKNOWN,
                        "poll: still unknown", "poll");
                if (saga.createdAt() != null && saga.createdAt().isBefore(ttlBoundary)) {
                    log.error("[melt-saga][alert] payment_unknown_ttl_exceeded saga_id={} quote_id={} age={} ttl={}",
                            saga.meltSagaId(), saga.quoteId(),
                            Duration.between(saga.createdAt(), Instant.now()),
                            paymentUnknownTtl);
                }
            }
        }
    }

    /**
     * Settles the proof rows bound to a saga when the reconciler advances
     * it out of {@code PAYMENT_UNKNOWN}. Without this call the saga state
     * would be out of sync with the proof state (PENDING rows stranded).
     *
     * @param meltSagaId  saga whose proofs to settle
     * @param spent       true → {@code commitSpentForHold} (Success branch);
     *                    false → {@code refundForHold} (DefinitiveFailure branch)
     */
    private void settleProofs(String meltSagaId, boolean spent) {
        if (proofVaultService == null) {
            log.warn("[melt-saga][alert] reconcile_proof_settle_skipped saga_id={} reason=no_proof_vault_service",
                    meltSagaId);
            return;
        }
        try {
            int affected = spent
                    ? proofVaultService.commitSpentForHold(meltSagaId)
                    : proofVaultService.refundForHold(meltSagaId);
            log.info("[melt-saga] reconcile_proof_settle saga_id={} spent={} affected={}",
                    meltSagaId, spent, affected);
        } catch (Exception settleError) {
            // Operator alert — saga is now terminal but proofs may still
            // be PENDING. Manual reconciliation required.
            log.error("[melt-saga][alert] reconcile_proof_settle_failed saga_id={} spent={} cause={}",
                    meltSagaId, spent, settleError.getMessage());
        }
    }

    /**
     * Sweeps {@code PROOFS_HELD} sagas older than the configured TTL into
     * {@code FAILED}. This protects against the JVM-crash-between-commit-and-
     * gateway-pay race (research R9) where a saga could otherwise hold
     * proofs indefinitely.
     */
    void sweepStaleProofsHeld() {
        List<MeltSaga> held = sagaRepository.findByState(MeltSagaState.PROOFS_HELD);
        Instant ttlBoundary = Instant.now().minus(proofsHeldTtl);
        for (MeltSaga saga : held) {
            if (saga.createdAt() == null || saga.createdAt().isAfter(ttlBoundary)) {
                continue;
            }
            int updated = sagaRepository.casState(
                    saga.meltSagaId(), MeltSagaState.PROOFS_HELD, MeltSagaState.FAILED);
            if (updated == 1) {
                // Spec 002 T011 — refund the proofs back to UNSPENT and
                // clear the saga binding, so the wallet's proofs become
                // spendable again after the TTL-triggered cleanup.
                if (proofVaultService != null) {
                    try {
                        int refunded = proofVaultService.refundForHold(saga.meltSagaId());
                        log.info("[melt-saga] ttl_sweep_refund saga_id={} refunded={}",
                                saga.meltSagaId(), refunded);
                    } catch (Exception refundError) {
                        log.warn("[melt-saga][alert] ttl_sweep_refund_failed saga_id={} cause={}",
                                saga.meltSagaId(), refundError.getMessage());
                    }
                }
                sagaRepository.recordTransition(saga.meltSagaId(),
                        MeltSagaState.PROOFS_HELD, MeltSagaState.FAILED,
                        "proofs_held_ttl_expired", "sweep");
                log.warn("[melt-saga][alert] proofs_held_ttl_expired saga_id={} quote_id={} age={} ttl={}",
                        saga.meltSagaId(), saga.quoteId(),
                        Duration.between(saga.createdAt(), Instant.now()),
                        proofsHeldTtl);
            }
        }
    }
}
