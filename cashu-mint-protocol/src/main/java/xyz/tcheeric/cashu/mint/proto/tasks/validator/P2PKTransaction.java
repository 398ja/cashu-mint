package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut11.P2PKSecret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;

import java.util.List;
import java.util.Objects;

/**
 * The transaction a NUT-11 spending condition is being evaluated inside.
 *
 * <p>{@code SIG_INPUTS} can be decided one input at a time, but {@code SIG_ALL} cannot: it signs
 * one message over the whole transaction, carried in the first input's witness. This type is that
 * transaction — the inputs, the outputs, and (for a melt) the quote id being paid — so the
 * aggregated message and the uniformity precondition have somewhere to live.
 *
 * @param inputs  the proofs being spent, in request order
 * @param outputs the blinded messages being issued, in request order; NUT-08 blank outputs for a melt
 * @param quoteId the melt quote being paid, or {@code null} for a swap
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/11.md">NUT-11</a>
 */
public record P2PKTransaction(@NonNull List<? extends Proof<? extends Secret>> inputs,
                              List<BlindedMessage> outputs,
                              String quoteId) {

    /** A swap: inputs and outputs, no quote. */
    public static P2PKTransaction forSwap(@NonNull List<? extends Proof<? extends Secret>> inputs,
                                          List<BlindedMessage> outputs) {
        return new P2PKTransaction(inputs, outputs, null);
    }

    /** A melt: inputs, the quote being paid, and the NUT-08 blank outputs. */
    public static P2PKTransaction forMelt(@NonNull List<? extends Proof<? extends Secret>> inputs,
                                          @NonNull String quoteId,
                                          List<BlindedMessage> blankOutputs) {
        return new P2PKTransaction(inputs, blankOutputs, quoteId);
    }

    /**
     * Whether {@code SIG_ALL} governs this transaction. NUT-11: "{@code SIG_INPUTS} is only
     * enforced if no input is {@code SIG_ALL}", so one {@code SIG_ALL} input switches the whole
     * transaction over.
     */
    public boolean isSigAll() throws CashuErrorException {
        for (Proof<? extends Secret> input : inputs) {
            if (input.getSecret() instanceof P2PKSecret secret && SignatureFlags.isSigAll(secret.getSigFlag())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The one input whose witness carries the signatures. NUT-11: "only the first input of a
     * transaction requires a witness that covers all other inputs and outputs".
     */
    public Proof<? extends Secret> witnessBearingInput() throws CashuErrorException {
        if (inputs.isEmpty()) {
            throw new CashuErrorException(CashuErrorCode.verify_proof_failed_error,
                    "SIG_ALL transaction carries no inputs");
        }
        return inputs.get(0);
    }

    /** The aggregated message every signature in the first input's witness must cover. */
    public SigAllMessage sigAllMessage() {
        return quoteId == null
                ? SigAllMessage.forSwap(inputs, outputs)
                : SigAllMessage.forMelt(inputs, quoteId, outputs);
    }

    /**
     * Enforces NUT-11's uniformity precondition: "If any one input has the signature flag
     * {@code SIG_ALL}, then all inputs are required to have the same kind, the flag
     * {@code SIG_ALL} and the same {@code Secret.data} and {@code Secret.tags}, otherwise an error
     * is returned."
     *
     * <p>The nonce is deliberately excluded — it is per-proof issuance randomness, not part of the
     * spending condition, and requiring it to match would make every multi-input {@code SIG_ALL}
     * transaction unspendable.
     *
     * @throws CashuErrorException if the inputs do not share one spending condition
     */
    public void requireUniformSpendingCondition() throws CashuErrorException {
        WellKnownSecret first = requireP2PK(witnessBearingInput());
        for (Proof<? extends Secret> input : inputs) {
            WellKnownSecret candidate = requireP2PK(input);
            if (!sameSpendingCondition(first, candidate)) {
                throw new CashuErrorException(CashuErrorCode.verify_proof_failed_error,
                        "SIG_ALL requires every input to carry the same P2PK spending condition");
            }
        }
    }

    private static boolean sameSpendingCondition(WellKnownSecret first, WellKnownSecret candidate) {
        return first.getKind() == candidate.getKind()
                && Objects.deepEquals(first.getData(), candidate.getData())
                && Objects.equals(first.getTags(), candidate.getTags());
    }

    /**
     * Under {@code SIG_ALL} every input must be a P2PK secret; a mixed transaction cannot share
     * one spending condition and NUT-11 requires an error rather than a partial evaluation.
     */
    private static P2PKSecret requireP2PK(Proof<? extends Secret> input) throws CashuErrorException {
        if (input.getSecret() instanceof P2PKSecret secret) {
            return secret;
        }
        throw new CashuErrorException(CashuErrorCode.verify_proof_failed_error,
                        "SIG_ALL requires every input to carry the same P2PK spending condition");
    }
}
