package xyz.tcheeric.cashu.mint.proto.tasks;

import xyz.tcheeric.cashu.common.model.Secret;
import xyz.tcheeric.cashu.common.model.rest.PostSwapRequest;
import xyz.tcheeric.cashu.common.model.rest.PostSwapResponse;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.mint.proto.nut.NUT02;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@AllArgsConstructor
public class VerifyFeesTask<T extends Secret> implements Task<Void> {

    private final PostSwapRequest<T> request;
    private final PostSwapResponse response;

    @Override
    public Void execute() throws CashuErrorException {
        validateFees();
        return null;
    }

    private void validateFees() throws CashuErrorException {
        var keySetId = request.getInputs().get(0).getKeySetId();
        var keySet = NUT02.keys(keySetId);
        var fees = request.getFees(keySet);
        var sum_inputs = request.getInputs().stream().mapToInt(proof -> proof.getAmount()).sum();
        var sum_outputs = response.getBlindSignatures().stream().mapToInt(blindSignature -> blindSignature.getAmount()).sum();

        if (sum_inputs - fees != sum_outputs) {
            throw new CashuErrorException("validate_fees_error");
        }
    }
}
