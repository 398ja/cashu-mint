package cashu.mint.nut;

import cashu.common.error.CashuException;
import cashu.common.model.BlindSignature;
import cashu.common.model.rest.PostSwapRequest;
import cashu.common.model.rest.PostSwapResponse;
import cashu.mint.actor.Mint;
import cashu.mint.actor.abilities.SignBlindedMessage;
import cashu.mint.actor.abilities.VerifyProof;
import lombok.NonNull;

import java.util.ArrayList;

public class NUT03 {

    public static PostSwapResponse swap(@NonNull PostSwapRequest request, @NonNull Mint mint) throws CashuException {
        new VerifyProof(mint, request).apply();

        var blindSignatures = new ArrayList<BlindSignature>();
        var blindedMessages = request.getBlindedMessages();
        blindedMessages.stream().forEach(bm -> {
            var blindSignature = new SignBlindedMessage(mint, bm).apply();
            blindSignatures.add(blindSignature);
        });

        return new PostSwapResponse(blindSignatures);
    }
}
