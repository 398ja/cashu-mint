package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.PostSwapResponse;
import xyz.tcheeric.cashu.mint.proto.nut.NUT02;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;

@Slf4j
@AllArgsConstructor
public class VerifyFeesTask<T extends Secret> implements Task<Void> {

    private final PostSwapRequest<T> request;
    private final PostSwapResponse response;
    private final MintLoadService mintLoadService;

    @Override
    public Void execute() throws CashuErrorException {
        validateFees();
        return null;
    }

    private void validateFees() throws CashuErrorException {
        var keySetId = request.getInputs().get(0).getKeySetId();
        var keySet = NUT02.keys(keySetId, mintLoadService);
        var fees = request.getFees(keySet);
        var sum_inputs = request.getInputs().stream().mapToInt(proof -> proof.getAmount()).sum();
        var sum_outputs = response.getBlindSignatures().stream().mapToInt(blindSignature -> blindSignature.getAmount()).sum();

        if (sum_inputs - fees != sum_outputs) {
            ErrorResponse error = new ErrorResponse("validate_fees_error");
            throw new CashuErrorException(error.toJson());
        }
    }
}
