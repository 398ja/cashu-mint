package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.model.BlindedMessage;
import xyz.tcheeric.cashu.common.model.Mint;
import xyz.tcheeric.cashu.common.model.Proof;
import xyz.tcheeric.cashu.common.model.Secret;
import xyz.tcheeric.cashu.common.model.rest.PostSwapRequest;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKSpendingCondition;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.SpendingCondition;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.RSSSpendingCondition;

import java.util.List;

@Slf4j
@AllArgsConstructor
public class VerifyProofsTask<T extends Secret> implements Task<Void> {

    private final Mint mint;
    private final PostSwapRequest<T> request;

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
            throw new CashuErrorException("validate_amounts_error");
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
        return switch (secret.getClass().getSimpleName()) {
            case "P2PKSecret" -> (SpendingCondition<T>) new P2PKSpendingCondition(blindedMessages);
            case "RandomStringSecret" -> (SpendingCondition<T>) new RSSSpendingCondition(mint);
            case null, default -> throw new IllegalArgumentException("Unsupported proof type");
        };
    }
}
