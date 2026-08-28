package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.error.ErrorResponse;
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
        var sum_inputs = request.getInputs().stream().mapToInt(Proof::getAmount).sum();
        var sum_outputs = request.getBlindedMessages().stream().mapToInt(BlindedMessage::getAmount).sum();

        if (sum_inputs - fees != sum_outputs) {
            log.warn("verify_fees transaction_not_balanced inputs={} fees={} outputs={}",
                    sum_inputs, fees, sum_outputs);
            throw new CashuErrorException(new ErrorResponse(CashuErrorCode.transaction_not_balanced.getKey(),
                    CashuErrorCode.transaction_not_balanced.getDefaultDetail()).toJson());
        }
    }
}
