package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.P2PKSecret;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKSpendingCondition;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.RSSSpendingCondition;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.SpendingCondition;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;

import java.util.List;

@Slf4j
@AllArgsConstructor
public class VerifyProofsTask<T extends Secret> implements Task<Void> {

    private final Mint mint;
    private final PostSwapRequest<T> request;
    private final MintProtocolService mintProtocolService;

    @Override
    public Void execute() throws CashuErrorException {

        validateAmounts();
        verifyProofs(request);

        return null;
    }

    private void validateAmounts() throws CashuErrorException {
        var proofs = request.getInputs();
        var blindedMessages = request.getBlindedMessages();

        int proofsAmount = proofs.stream().mapToInt(Proof::getAmount).sum();
        int blindedMessagesAmount = blindedMessages.stream().mapToInt(BlindedMessage::getAmount).sum();

        if (proofsAmount != blindedMessagesAmount) {
            ErrorResponse error = new ErrorResponse("validate_amounts_error");
            throw new CashuErrorException(error.toJson());
        }
    }

    private void verifyProofs(@NonNull PostSwapRequest<T> request) throws CashuErrorException {
        List<Proof<T>> proofs = request.getInputs();
        List<BlindedMessage> blindedMessages = request.getBlindedMessages();

        for (Proof<T> proof : proofs) {
            Secret secret = proof.getSecret();
            SpendingCondition<T> spendingCondition = getSpendingCondition(secret, blindedMessages);
            spendingCondition.verify(proof);
        }

    }

    private SpendingCondition<T> getSpendingCondition(@NonNull Secret secret, List<BlindedMessage> blindedMessages) {
        if (secret instanceof P2PKSecret) {
            return (SpendingCondition<T>) new P2PKSpendingCondition(blindedMessages);
        }
        if (secret instanceof RandomStringSecret) {
            return (SpendingCondition<T>) new RSSSpendingCondition(mint, mintProtocolService);
        }
        throw new IllegalArgumentException("Unsupported proof type");
    }
}
