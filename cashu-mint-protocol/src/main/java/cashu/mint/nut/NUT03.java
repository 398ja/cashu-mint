package cashu.mint.nut;

import cashu.common.model.BlindSignature;
import cashu.common.model.Mint;
import cashu.common.model.rest.PostSwapRequest;
import cashu.common.model.rest.PostSwapResponse;
import cashu.mint.actor.abilities.InvalidateProofs;
import cashu.mint.actor.abilities.SignBlindedMessage;
import cashu.mint.actor.abilities.VerifyProofs;
import cashu.vault.impl.fs.FSMintVault;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.Comparator;

public class NUT03 {

    public static PostSwapResponse swap(@NonNull PostSwapRequest request) {

        Mint mint = FSMintVault.load(false, true);

        // Verify proofs
        new VerifyProofs(mint, request).apply();

        // Invalidate proofs
        new InvalidateProofs(mint, request).apply();

        // Issue new signatures
        var blindSignatures = new ArrayList<BlindSignature>();
        var blindedMessages = request.getBlindedMessages();
        blindedMessages.forEach(bm -> {
            var blindSignature = new SignBlindedMessage(mint, bm).apply();
            blindSignatures.add(blindSignature);
        });

        // Sort blindSignatures by the amount in ascending order
        blindSignatures.sort(Comparator.comparing(blindSignature -> blindSignature.getAmount()));

        return new PostSwapResponse(blindSignatures);
    }
}