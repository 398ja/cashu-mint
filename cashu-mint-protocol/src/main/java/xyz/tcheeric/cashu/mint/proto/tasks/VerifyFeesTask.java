package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.mint.proto.nut.MintKeySetResolver;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;

/**
 * The single NUT-02 balance equation: {@code sum(inputs) - fees == sum(outputs)}.
 *
 * <p>The equation is read from the request alone, so this task runs before any blinded
 * message is signed. An unbalanced transaction that was signed first would leave blind
 * signatures in the vault for a swap the mint went on to reject.
 */
@Slf4j
@AllArgsConstructor
public class VerifyFeesTask<T extends Secret> extends InstrumentedTask<Void> {

    private final PostSwapRequest<T> request;
    private final MintLoadService mintLoadService;

    @Override
    protected Void doExecute() throws CashuErrorException {
        validateFees();
        return null;
    }

    private void validateFees() throws CashuErrorException {
        // NUT-02 keeps inactive keysets spendable, so a swap may mix inputs from several keysets.
        // Pricing them all from the first input's keyset mischarges every input issued under any
        // other keyset, in either direction.
        var fees = request.getFees(new MintKeySetResolver(mintLoadService));

        // Summed as long, not int. Amounts are ints and a swap may carry up to MAX_PROOFS inputs
        // and MAX_BLINDED_MESSAGES outputs, so an int accumulator can wrap: an attacker choosing
        // amounts whose true sum exceeds the outputs by a multiple of 2^32 would satisfy the
        // equality while taking more value out than they put in. A long accumulator cannot
        // overflow for any admissible number of 32-bit amounts, and every amount is already
        // required to be positive by VerifyProofsTask.
        long sum_inputs = request.getInputs().stream().mapToLong(Proof::getAmount).sum();
        long sum_outputs = request.getBlindedMessages().stream()
                .mapToLong(BlindedMessage::getAmount).sum();

        if (sum_inputs - fees != sum_outputs) {
            log.warn("verify_fees transaction_not_balanced inputs={} fees={} outputs={}",
                    sum_inputs, fees, sum_outputs);
            throw new CashuErrorException(CashuErrorCode.transaction_not_balanced);
        }
    }
}
