package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.domain.SwapHoldPhase;
import xyz.tcheeric.cashu.mint.proto.ports.SwapHoldRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An exclusive, durable claim on a swap's input proofs, held across the signing step.
 *
 * <p>A swap signs its outputs into the signature vault before it can spend its inputs, and the
 * two writes cannot share a transaction because the vault is reached over its REST API. Signing
 * first and spending afterwards means a failure in between leaves outputs a wallet can recover
 * through NUT-09 restore while the inputs are still spendable, so the same value is redeemable
 * twice (issue #400).
 *
 * <p>The melt path already answers this class of problem with a saga: it takes a durable,
 * exclusive hold on the proofs <em>before</em> the irreversible step, then either commits the
 * hold or compensates it. This is the same shape, using the same vault primitives:
 *
 * <ol>
 *   <li>{@link #claim(List)} moves every input to {@code PENDING} bound to this hold, in one
 *       atomic insert-or-claim per proof, and refuses the swap unless all of them bind.</li>
 *   <li>The caller signs.</li>
 *   <li>{@link #commit()} flips the whole hold to {@code SPENT} in a single set-based call.</li>
 *   <li>{@link #release()} compensates a failure that happened before anything was signed,
 *       returning the inputs to {@code UNSPENT}.</li>
 * </ol>
 *
 * <p>The hold is what closes the hazard, not the proof state: the vault's claim is gated on
 * {@code state = 'UNSPENT' AND melt_saga_id IS NULL}, so proofs held by an unresolved swap
 * cannot be bound by any later swap or melt even though they are not yet {@code SPENT}.
 *
 * <p>Claiming and committing are each one call for the whole input list rather than a loop, so
 * there is no mid-list outcome in which some inputs are spent and others are not.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/03.md">NUT-03</a>
 */
@Slf4j
class SwapProofHold {

    /**
     * Marks a hold as belonging to a swap rather than a melt saga.
     *
     * <p>Both share the vault's {@code melt_saga_id} binding column, and an operator resolving a
     * stranded hold needs to know which flow produced it, because the two resolve differently.
     */
    private static final String SWAP_HOLD_PREFIX = "swap-";

    private final UUID mintId;
    private final MintVaultService mintVaultService;
    private final ProofVaultService proofVaultService;
    private final SwapHoldRepository holdRepository;
    private final String holdId;
    private int heldCount;

    SwapProofHold(@NonNull UUID mintId,
                  @NonNull MintVaultService mintVaultService,
                  @NonNull ProofVaultService proofVaultService,
                  @NonNull SwapHoldRepository holdRepository) {
        this.mintId = mintId;
        this.holdRepository = holdRepository;
        this.mintVaultService = mintVaultService;
        this.proofVaultService = proofVaultService;
        this.holdId = SWAP_HOLD_PREFIX + UUID.randomUUID();
    }

    String holdId() {
        return holdId;
    }

    /**
     * Takes an exclusive hold on every input, or on none of them.
     *
     * <p>A partial claim is released before returning, so a caller that sees an exception knows
     * no input is left bound to this hold.
     *
     * @throws CashuErrorException {@link CashuErrorCode#proofs_not_bound} when any input could
     *                             not be claimed, which includes an input already spent or
     *                             already held by another swap or melt
     */
    <T extends Secret> void claim(@NonNull List<Proof<T>> inputs) throws CashuErrorException {
        int bound = claimCount(inputs);
        if (bound < inputs.size()) {
            log.warn("[swap-hold] proofs_not_bound hold_id={} expected={} bound={}",
                    holdId, inputs.size(), bound);
            release();
            throw new CashuErrorException(CashuErrorCode.proofs_not_bound);
        }
        heldCount = bound;
        holdRepository.open(holdId, bound);
        log.debug("[swap-hold] inputs_held hold_id={} count={}", holdId, bound);
    }

    private <T extends Secret> int claimCount(List<Proof<T>> inputs) throws CashuErrorException {
        try {
            return proofVaultService.insertOrClaimForSaga(vaultRowsFor(inputs), holdId, mintId);
        } catch (CashuErrorException | RuntimeException claimError) {
            log.error("[swap-hold] claim_failed hold_id={}", holdId, claimError);
            release();
            throw new CashuErrorException(CashuErrorCode.proofs_not_bound);
        }
    }

    /**
     * Records that signing is about to begin, fixing how a stranded hold must be resolved.
     *
     * <p>Written <em>before</em> the first signature rather than after, because a crash either
     * side of this write must be read the same way: signing may have begun, so the hold can only
     * be committed. Recording it afterwards would leave the dangerous case indistinguishable from
     * an untouched hold, and releasing that hold is the double-spend this class prevents.
     *
     * <p>The cost of the conservative reading is a hold that is committed although nothing was
     * signed, which spends inputs and returns no outputs. That is a loss for one wallet rather
     * than inflation of the mint's supply, and it is recoverable by an operator from the hold
     * record; the opposite mistake is neither.
     */
    void markSigning() {
        holdRepository.advance(holdId, SwapHoldPhase.SIGNING);
    }

    /**
     * Spends the held inputs, completing the swap.
     *
     * <p>The caller has signed by this point, so a failure here is the pathological case: the
     * outputs exist and the inputs must not become spendable again. The hold is deliberately
     * left in place — see {@link #describeStrandedHold()}.
     *
     * <p>A commit that reports fewer proofs spent than were held is treated as a failure rather
     * than a success. Silently accepting it would let a vault that spent nothing at all — for
     * instance one whose commit is an unimplemented no-op — return a swap whose outputs are
     * signed and whose inputs are still there, which is exactly the hazard being closed.
     */
    void commit() throws CashuErrorException {
        int spent = proofVaultService.commitSpentForSaga(holdId);
        if (spent < heldCount) {
            throw new CashuErrorException(CashuErrorCode.proofs_pending,
                    "swap held " + heldCount + " inputs but only " + spent + " were spent");
        }
        holdRepository.advance(holdId, SwapHoldPhase.COMMITTED);
        log.debug("[swap-hold] inputs_spent hold_id={} count={}", holdId, spent);
    }

    /**
     * Returns the held inputs to {@code UNSPENT}.
     *
     * <p>Only safe before anything has been signed. Releasing a hold whose outputs are already
     * signed would recreate the double-spend this class exists to prevent.
     *
     * @return whether the inputs are known to be spendable again
     */
    boolean release() {
        try {
            int refunded = proofVaultService.refundForSaga(holdId);
            holdRepository.advance(holdId, SwapHoldPhase.RELEASED);
            log.debug("[swap-hold] inputs_released hold_id={} count={}", holdId, refunded);
            return true;
        } catch (CashuErrorException | RuntimeException releaseError) {
            log.error("[swap-hold][alert] release_failed hold_id={} cause={} — inputs stay PENDING "
                            + "for operator resolution",
                    holdId, releaseError.getMessage());
            return false;
        }
    }

    /**
     * The operator-facing account of a hold that could not be resolved automatically.
     *
     * <p>Its inputs stay {@code PENDING} and bound, which is the one state that is safe under
     * either reading: no wallet can spend them again, and no value has been destroyed, so an
     * operator can still commit or release the hold once the outcome is known. The rule is that
     * a hold stranded after signing must be committed, never released.
     */
    String describeStrandedHold() {
        return "swap inputs remain held under " + holdId
                + "; the outputs are signed, so this hold must be committed, never released";
    }

    private <T extends Secret> List<ProofEntity> vaultRowsFor(List<Proof<T>> inputs)
            throws CashuErrorException {
        MintEntity mintEntity = mintVaultService.retrieveMint(mintId.toString());
        List<ProofEntity> rows = new ArrayList<>(inputs.size());
        for (Proof<T> input : inputs) {
            ProofEntity row = ProofEntity.fromProof(input, mintEntity);
            keyUnderTheEncodingTheProofWasIssuedUnder(input, row);
            rows.add(row);
        }
        return rows;
    }

    /**
     * Keys the row onto the curve point this proof is already recorded under, if any.
     *
     * <p>The NUT-00 secret encoding correction gave every secret two possible points, and a proof
     * issued before it was corrected is recorded under the legacy one. Claiming such a proof
     * under the spec point would insert a second row for one logical proof and leave the legacy
     * row untouched and spendable, which is the double-spend this hold exists to prevent.
     */
    private <T extends Secret> void keyUnderTheEncodingTheProofWasIssuedUnder(Proof<T> proof,
                                                                             ProofEntity row)
            throws CashuErrorException {
        if (proof.getSecret() == null) {
            return;
        }
        row.setSecret(proofVaultService.storageKeyFor(proof.getSecret().toString()));
    }
}
