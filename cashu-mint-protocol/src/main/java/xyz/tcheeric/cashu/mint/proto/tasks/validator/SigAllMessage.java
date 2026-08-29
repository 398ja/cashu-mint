package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The single message a NUT-11 {@code SIG_ALL} transaction is signed over.
 *
 * <p>{@code SIG_ALL} commits to the whole transaction shape rather than to each input in
 * isolation, so the message is one concatenation covering every input and every output. Because
 * the concatenation is ordered, reordering or substituting an output changes the message and
 * invalidates the signature — which is the guarantee {@code SIG_ALL} exists to give.
 *
 * <p>Swap (NUT-03):
 * <pre>secret_0 || C_0 || ... || secret_n || C_n || amount_0 || B__0 || ... || amount_m || B__m</pre>
 *
 * <p>Melt (NUT-05) appends the quote id, binding the signature to the invoice it pays:
 * <pre>... || amount_m || B__m || quote_id</pre>
 *
 * <p>The quote id is what stops a witness captured from one melt being replayed against a
 * different quote.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/11.md">NUT-11 — message aggregation</a>
 */
public record SigAllMessage(@NonNull String value) {

    /**
     * The message for a swap: all inputs, then all outputs.
     *
     * @param inputs  the transaction's proofs, in request order
     * @param outputs the transaction's blinded messages, in request order
     */
    public static SigAllMessage forSwap(@NonNull List<? extends Proof<? extends Secret>> inputs,
                                        List<BlindedMessage> outputs) {
        return new SigAllMessage(aggregate(inputs, outputs, ""));
    }

    /**
     * The message for a melt: all inputs, then the NUT-08 blank outputs, then the quote id.
     *
     * @param inputs       the transaction's proofs, in request order
     * @param quoteId      the melt quote being paid
     * @param blankOutputs the NUT-08 blank outputs, in request order; may be empty
     */
    public static SigAllMessage forMelt(@NonNull List<? extends Proof<? extends Secret>> inputs,
                                        @NonNull String quoteId,
                                        List<BlindedMessage> blankOutputs) {
        return new SigAllMessage(aggregate(inputs, blankOutputs, quoteId));
    }

    /**
     * The message as signed bytes. NUT-11 signs the UTF-8 encoding of the concatenated string.
     */
    public byte[] toBytes() {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String aggregate(List<? extends Proof<? extends Secret>> inputs,
                                    List<BlindedMessage> outputs,
                                    String trailer) {
        StringBuilder message = new StringBuilder();
        for (Proof<? extends Secret> input : inputs) {
            message.append(secretOf(input)).append(unblindedSignatureOf(input));
        }
        if (outputs != null) {
            for (BlindedMessage output : outputs) {
                message.append(output.getAmount()).append(output.getBlindedMessage());
            }
        }
        return message.append(trailer).toString();
    }

    /**
     * The unescaped secret string. {@code Proof.secret} carries escaped JSON on the wire; NUT-11
     * requires the message to be built from the unescaped form, which is what the parsed secret's
     * own string representation gives us.
     */
    private static String secretOf(Proof<? extends Secret> input) {
        return input.getSecret().toString();
    }

    /** The input's unblinded signature {@code C}, as the hex string NUT-11 concatenates. */
    private static String unblindedSignatureOf(Proof<? extends Secret> input) {
        return input.getUnblindedSignature() == null ? "" : input.getUnblindedSignature().toString();
    }
}
