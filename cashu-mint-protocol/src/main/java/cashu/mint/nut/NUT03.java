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
import lombok.extern.java.Log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.logging.Level;

@Log
public class NUT03 {

    public static PostSwapResponse swap(@NonNull PostSwapRequest request) {

        Mint mint = FSMintVault.load(false, true);

        // Verify proofs
        Boolean isProofsValid = new VerifyProofs(mint, request).apply();
        if (!isProofsValid) {
            log.log(Level.SEVERE, "Proofs are not valid");
            return null;
        }

        // Invalidate proofs
        Boolean isInvalidated = new InvalidateProofs(mint, request).apply();
        if(!isInvalidated) {
            log.log(Level.SEVERE, "Failed to invalidate proofs");
            return null;
        }

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