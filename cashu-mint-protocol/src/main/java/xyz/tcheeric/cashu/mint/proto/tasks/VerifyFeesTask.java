package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.PostSwapResponse;
import xyz.tcheeric.cashu.mint.proto.service.KeySetService;
import xyz.tcheeric.cashu.mint.proto.service.DefaultKeySetService;

@Slf4j
public class VerifyFeesTask<T extends Secret> implements Task<Void> {

    private final PostSwapRequest<T> request;
    private final PostSwapResponse response;
    private final KeySetService keySetService;

    public VerifyFeesTask(PostSwapRequest<T> request, PostSwapResponse response) {
        this(request, response, new DefaultKeySetService());
    }

    public VerifyFeesTask(PostSwapRequest<T> request, PostSwapResponse response, KeySetService keySetService) {
        this.request = request;
        this.response = response;
        this.keySetService = keySetService;
    }

    @Override
    public Void execute() throws CashuErrorException {
        validateFees();
        return null;
    }

    private void validateFees() throws CashuErrorException {
        var keySetId = request.getInputs().get(0).getKeySetId();
        var keySet = keySetService.getKeySet(keySetId);
        var fees = request.getFees(keySet);
        var sum_inputs = request.getInputs().stream().mapToInt(proof -> proof.getAmount()).sum();
        var sum_outputs = response.getBlindSignatures().stream().mapToInt(blindSignature -> blindSignature.getAmount()).sum();

        if (sum_inputs - fees != sum_outputs) {
            throw new CashuErrorException("validate_fees_error");
        }
    }
}
