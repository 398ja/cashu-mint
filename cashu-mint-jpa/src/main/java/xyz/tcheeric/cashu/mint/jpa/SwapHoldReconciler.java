package xyz.tcheeric.cashu.mint.jpa;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.domain.SwapHoldPhase;
import xyz.tcheeric.cashu.mint.proto.ports.SwapHold;
import xyz.tcheeric.cashu.mint.proto.ports.SwapHoldRepository;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Resolves swap holds whose swap never finished (issue #400).
 *
 * <p>A swap claims its inputs before signing and spends them after. If the process dies in
 * between, the inputs stay held: unspendable by anyone, so no value can double, but also never
 * resolved. This sweep resolves them.
 *
 * <p><strong>It sweeps in the opposite direction to {@link MeltSagaReconciler}</strong>, which is
 * the one thing to keep in mind when reading the two together. A stale melt {@code PROOFS_HELD} is
 * failed and its proofs <em>released</em>: no payment went out, so the wallet should get its money
 * back. A stale swap in {@link SwapHoldPhase#SIGNING} must be <em>committed</em>: an output may
 * already be signed and redeemable through NUT-09 restore, and releasing the inputs on top of that
 * is exactly the double-spend the hold exists to prevent. Only a hold still in
 * {@link SwapHoldPhase#HELD}, which by construction has signed nothing, is released.
 *
 * <p>The asymmetry follows from which side of its irreversible step each flow is stranded on. Melt
 * holds proofs <em>before</em> paying and can still decide not to pay; a swap in {@code SIGNING}
 * is already past the point where it could take the outputs back.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
public class SwapHoldReconciler {

  private final SwapHoldRepository holds;
  private final ProofVaultService proofVaultService;
  private final Duration holdTtl;

  public SwapHoldReconciler(
      @Autowired(required = false) SwapHoldRepository holds,
      @Autowired(required = false) ProofVaultService proofVaultService,
      @Value("${cashu.mint.swap.hold-ttl:PT5M}") Duration holdTtl) {
    this.holds = holds;
    this.proofVaultService = proofVaultService;
    this.holdTtl = holdTtl;
  }

  @Scheduled(fixedDelayString = "${cashu.mint.swap.reconcile-interval:PT60S}")
  public void reconcileTick() {
    if (holds == null || proofVaultService == null) {
      log.debug("swap_hold_reconcile_skipped reason=no_swap_hold_dependencies");
      return;
    }
    try {
      sweepStaleHolds();
    } catch (RuntimeException e) {
      // One bad tick must not stop the schedule: the next sweep is the recovery.
      log.warn("swap_hold_reconcile sweep_failed cause={}", e.getMessage());
    }
  }

  void sweepStaleHolds() {
    List<SwapHold> stale = holds.findUnresolvedOlderThan(Instant.now().minus(holdTtl));
    for (SwapHold hold : stale) {
      resolve(hold);
    }
  }

  /**
   * Resolves one stale hold in the direction its phase demands.
   *
   * <p>A hold whose phase cannot be read is left alone. Doing nothing keeps the inputs held, which
   * is the state that is safe under either reading; acting on a guess is not.
   */
  private void resolve(SwapHold hold) {
    switch (hold.phase()) {
      case SIGNING -> commitPastThePointOfNoReturn(hold);
      case HELD -> releaseNothingWasSigned(hold);
      default ->
          log.warn(
              "swap_hold_reconcile unexpected_phase hold_id={} phase={}",
              hold.holdId(),
              hold.phase());
    }
  }

  /**
   * Spends the inputs of a hold that had begun signing.
   *
   * <p>Committing is correct even when no signature was actually produced, because the phase is
   * written before the first one: the conservative reading costs one wallet its inputs, which an
   * operator can make good from this record, while the other mistake inflates the mint's supply
   * and cannot be undone.
   */
  private void commitPastThePointOfNoReturn(SwapHold hold) {
    try {
      int spent = proofVaultService.commitSpentForHold(hold.holdId());
      holds.advance(hold.holdId(), SwapHoldPhase.COMMITTED);
      log.warn(
          "swap_hold_reconcile committed hold_id={} inputs={} spent={} reason=stranded_after_signing",
          hold.holdId(),
          hold.inputCount(),
          spent);
    } catch (CashuErrorException | RuntimeException e) {
      log.error(
          "[swap-hold][alert] SWAP_HOLD_COMMIT_FAILED hold_id={} cause={} — inputs stay held",
          hold.holdId(),
          e.getMessage());
    }
  }

  /** Returns the inputs of a hold that never reached signing, so the wallet can spend them again. */
  private void releaseNothingWasSigned(SwapHold hold) {
    try {
      int refunded = proofVaultService.refundForHold(hold.holdId());
      holds.advance(hold.holdId(), SwapHoldPhase.RELEASED);
      log.info(
          "swap_hold_reconcile released hold_id={} inputs={} refunded={} reason=stranded_before_signing",
          hold.holdId(),
          hold.inputCount(),
          refunded);
    } catch (CashuErrorException | RuntimeException e) {
      log.error(
          "[swap-hold][alert] SWAP_HOLD_RELEASE_FAILED hold_id={} cause={} — inputs stay held",
          hold.holdId(),
          e.getMessage());
    }
  }
}
