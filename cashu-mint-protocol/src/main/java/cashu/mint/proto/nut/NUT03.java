package cashu.mint.proto.nut;

import cashu.common.model.BlindSignature;
import cashu.common.model.BlindedMessage;
import cashu.common.model.Mint;
import cashu.common.model.rest.PostSwapRequest;
import cashu.common.model.rest.PostSwapResponse;
import cashu.common.util.CashuErrorException;
import cashu.mint.proto.tasks.InvalidateProofsTask;
import cashu.mint.proto.tasks.SignBlindedMessageTask;
import cashu.mint.proto.tasks.VerifyProofsTask;
import cashu.vault.impl.fs.FSMintVault;
import lombok.NonNull;
import lombok.extern.java.Log;

import java.util.ArrayList;
import java.util.List;

// TEST - When calling swap, ensure that the VerifyProofs and InvalidateProofs tasks are executed
@Log
public class NUT03 {

    public static PostSwapResponse swap(@NonNull PostSwapRequest postSwapRequest) throws CashuErrorException {

        // Load mint
        Mint mint = FSMintVault.load(false, false);

        if (mint == null) {
            throw new CashuErrorException("swap_mint_not_found");
        }

        // Verify proofs
        new VerifyProofsTask(mint, postSwapRequest).execute();

        // Invalidate proofs
        new InvalidateProofsTask(mint, postSwapRequest.getProofs()).execute();

        // Issue new signatures
        List<BlindSignature> blindSignatures = new ArrayList<>();
        List<BlindedMessage> blindedMessages = postSwapRequest.getBlindedMessages();
        blindedMessages.forEach(bm -> {
            BlindSignature blindSignature  = new SignBlindedMessageTask(mint, bm).execute();
            blindSignatures.add(blindSignature);
        });

        // Sort blindSignatures by the amount in ascending order
        // TODO: This is a temporary solution for now. Ultimately, we may need to ensure that the blind signatures are sorted in ascending order
        // blindSignatures.sort(Comparator.comparing(blindSignature -> blindSignature.getAmount()));

        return new PostSwapResponse(blindSignatures);
    }

}