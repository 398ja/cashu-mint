package cashu.mint.proto.tasks;

import cashu.common.model.KeySet;
import cashu.common.model.rest.PostSwapRequest;
import cashu.common.model.rest.PostSwapResponse;
import cashu.common.util.CashuErrorException;
import cashu.common.util.Task;
import cashu.mint.proto.nut.NUT02;
import lombok.AllArgsConstructor;
import lombok.extern.java.Log;

@Log
@AllArgsConstructor
public class VerifyFeesTask implements Task<Void> {

    private final PostSwapRequest request;
    private final PostSwapResponse response;

    @Override
    public Void execute() throws CashuErrorException {
        validateFees();
        return null;
    }

    private void validateFees() throws CashuErrorException {
        var keySetId = request.getProofs().get(0).getKeySetId();
        var keySet = NUT02.keys(keySetId);
        var fees = request.getFees(keySet);
        var sum_inputs = request.getProofs().stream().mapToInt(proof -> proof.getAmount()).sum();
        var sum_outputs = response.getBlindSignatures().stream().mapToInt(blindSignature -> blindSignature.getAmount()).sum();

        if (sum_inputs - fees != sum_outputs) {
            throw new CashuErrorException("validate_fees_error");
        }
    }
}
