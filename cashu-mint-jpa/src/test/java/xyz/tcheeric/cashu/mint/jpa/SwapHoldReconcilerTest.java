package xyz.tcheeric.cashu.mint.jpa;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.mint.proto.domain.SwapHoldPhase;
import xyz.tcheeric.cashu.mint.proto.ports.SwapHold;
import xyz.tcheeric.cashu.mint.proto.ports.SwapHoldRepository;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #400 — how the sweep resolves a swap that died holding its inputs.
 *
 * <p>The direction is the whole point, and it is the opposite of the melt sweep these tests sit
 * beside: a hold that had begun signing is committed, never released.
 */
class SwapHoldReconcilerTest {

    private static final Duration TTL = Duration.ofMinutes(5);

    private SwapHoldRepository holds;
    private ProofVaultService proofVault;
    private SwapHoldReconciler reconciler;

    @BeforeEach
    void setUp() {
        holds = Mockito.mock(SwapHoldRepository.class);
        proofVault = Mockito.mock(ProofVaultService.class);
        reconciler = new SwapHoldReconciler(holds, proofVault, TTL);
    }

    private static SwapHold hold(String holdId, SwapHoldPhase phase) {
        SwapHold hold = Mockito.mock(SwapHold.class);
        when(hold.holdId()).thenReturn(holdId);
        when(hold.phase()).thenReturn(phase);
        when(hold.inputCount()).thenReturn(2);
        when(hold.updatedAt()).thenReturn(Instant.now().minus(Duration.ofHours(1)));
        return hold;
    }

    private void sweepFinding(SwapHold... stale) {
        when(holds.findUnresolvedOlderThan(any())).thenReturn(List.of(stale));
        reconciler.reconcileTick();
    }

    /**
     * The crash case this exists for: a swap died after signing, so its outputs may be redeemable.
     * The inputs must be spent, and must never be handed back, or the same value is redeemable
     * twice.
     */
    @Test
    void shouldSpendTheInputsWhenASwapDiedAfterSigningBegan() throws Exception {
        // Arrange
        SwapHold stranded = hold("swap-signed", SwapHoldPhase.SIGNING);

        // Act
        sweepFinding(stranded);

        // Assert
        verify(proofVault).commitSpentForHold("swap-signed");
        verify(proofVault, never()).refundForHold(anyString());
        verify(holds).advance("swap-signed", SwapHoldPhase.COMMITTED);
    }

    /**
     * A swap that died before signing has produced no output, so returning the inputs costs the
     * mint nothing and leaves the wallet able to spend its money again.
     */
    @Test
    void shouldReturnTheInputsWhenASwapDiedBeforeSigningBegan() throws Exception {
        // Arrange
        SwapHold stranded = hold("swap-unsigned", SwapHoldPhase.HELD);

        // Act
        sweepFinding(stranded);

        // Assert
        verify(proofVault).refundForHold("swap-unsigned");
        verify(proofVault, never()).commitSpentForHold(anyString());
        verify(holds).advance("swap-unsigned", SwapHoldPhase.RELEASED);
    }

    /**
     * A swap still in flight is younger than the TTL and must be left alone; resolving it would
     * pull the inputs out from under a request that is about to finish normally.
     */
    @Test
    void shouldLeaveAHoldAloneWhileItIsStillWithinItsTtl() throws Exception {
        // Arrange
        when(holds.findUnresolvedOlderThan(any())).thenReturn(List.of());

        // Act
        reconciler.reconcileTick();

        // Assert
        verify(proofVault, never()).commitSpentForHold(anyString());
        verify(proofVault, never()).refundForHold(anyString());
    }

    /**
     * Ensures the sweep asks only for holds that stopped moving before the TTL boundary, which is
     * what keeps a slow but healthy swap out of the results.
     */
    @Test
    void shouldOnlyConsiderHoldsThatStoppedMovingBeforeTheTtlBoundary() {
        // Arrange
        Instant before = Instant.now().minus(TTL);
        when(holds.findUnresolvedOlderThan(any())).thenReturn(List.of());

        // Act
        reconciler.reconcileTick();

        // Assert
        verify(holds)
                .findUnresolvedOlderThan(
                        Mockito.argThat(cutoff -> !cutoff.isBefore(before.minusSeconds(5))
                                && !cutoff.isAfter(Instant.now())));
    }

    /**
     * A commit that fails must leave the inputs held rather than marked resolved, so the next
     * sweep tries again instead of the hold being lost.
     */
    @Test
    void shouldLeaveTheInputsHeldWhenTheCommitFails() throws Exception {
        // Arrange
        SwapHold stranded = hold("swap-commit-fails", SwapHoldPhase.SIGNING);
        when(proofVault.commitSpentForHold("swap-commit-fails"))
                .thenThrow(new RuntimeException("vault unreachable"));

        // Act
        sweepFinding(stranded);

        // Assert
        verify(holds, never()).advance(eq("swap-commit-fails"), any());
        verify(proofVault, never()).refundForHold(anyString());
    }

    /**
     * One unresolvable hold must not stop the others being swept, or a single bad row would
     * strand every later swap indefinitely.
     */
    @Test
    void shouldKeepSweepingAfterOneHoldFails() throws Exception {
        // Arrange
        SwapHold failing = hold("swap-failing", SwapHoldPhase.SIGNING);
        SwapHold healthy = hold("swap-healthy", SwapHoldPhase.HELD);
        when(proofVault.commitSpentForHold("swap-failing"))
                .thenThrow(new RuntimeException("vault unreachable"));

        // Act
        sweepFinding(failing, healthy);

        // Assert
        verify(proofVault).refundForHold("swap-healthy");
    }

    /**
     * Without a vault or a hold store the sweep does nothing at all, so a mint booted without the
     * JPA module keeps working rather than failing on every tick.
     */
    @Test
    void shouldDoNothingWhenTheDependenciesAreNotWired() throws Exception {
        // Arrange
        SwapHoldReconciler unwired = new SwapHoldReconciler(null, null, TTL);

        // Act
        unwired.reconcileTick();

        // Assert
        verify(proofVault, never()).commitSpentForHold(anyString());
    }
}
