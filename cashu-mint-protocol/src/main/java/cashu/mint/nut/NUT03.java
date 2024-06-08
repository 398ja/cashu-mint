package cashu.mint.nut;

import cashu.common.model.BlindSignature;
import cashu.common.model.Mint;
import cashu.common.model.rest.PostSwapRequest;
import cashu.common.model.rest.PostSwapResponse;
import cashu.common.protocol.CashuErrorException;
import cashu.mint.actor.abilities.InvalidateProofs;
import cashu.mint.actor.abilities.SignBlindedMessage;
import cashu.mint.actor.abilities.VerifyProofs;
import cashu.mint.actor.abilities.tasks.InvalidateProofsTask;
import cashu.mint.actor.abilities.tasks.SignBlindedMessageTask;
import cashu.mint.actor.abilities.tasks.VerifyProofsTask;
import cashu.vault.impl.fs.FSMintVault;
import lombok.NonNull;
import lombok.extern.java.Log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

@Log
public class NUT03 {

    public static PostSwapResponse swap(@NonNull PostSwapRequest request) throws CashuErrorException {

        Mint mint = FSMintVault.load(false, false);

        log.log(Level.INFO, ">>> Mint: {0}", mint.getPrivateKey().toString());

        // Verify proofs
        new VerifyProofs(new VerifyProofsTask(mint, request)).apply();

        // Invalidate proofs
        new InvalidateProofs(new InvalidateProofsTask(mint, request)).apply();

        AtomicReference<CashuErrorException> error = new AtomicReference<>();
        // Issue new signatures
        var blindSignatures = new ArrayList<BlindSignature>();
        var blindedMessages = request.getBlindedMessages();
        blindedMessages.forEach(bm -> {
            BlindSignature blindSignature = null;
            try {
                blindSignature = new SignBlindedMessage(new SignBlindedMessageTask(mint, bm)).apply();
            } catch (CashuErrorException e) {
                error.set(e);
            }
            blindSignatures.add(blindSignature);
        });

        if(error.get() != null) {
            throw error.get();
        }

        // Sort blindSignatures by the amount in ascending order
        blindSignatures.sort(Comparator.comparing(blindSignature -> blindSignature.getAmount()));

        return new PostSwapResponse(blindSignatures);
    }

}