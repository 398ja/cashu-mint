package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.PostSwapResponse;
import xyz.tcheeric.cashu.mint.proto.tasks.InvalidateProofsTask;
import xyz.tcheeric.cashu.mint.proto.tasks.SignBlindedMessageTask;
import xyz.tcheeric.cashu.mint.proto.tasks.VerifyFeesTask;
import xyz.tcheeric.cashu.mint.proto.tasks.VerifyProofsTask;
import xyz.tcheeric.cashu.mint.proto.service.DefaultMintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolServiceFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// TEST - When calling swap, ensure that the VerifyProofs and InvalidateProofs tasks are executed
@Slf4j
@Nut(3)
public class NUT03 {

    public static <T extends Secret> PostSwapResponse swap(@NonNull UUID mintId, @NonNull PostSwapRequest<T> postSwapRequest) throws CashuErrorException {
        return swap(mintId, postSwapRequest, new DefaultMintLoadService());
    }

    public static <T extends Secret> PostSwapResponse swap(@NonNull UUID mintId,
                                                           @NonNull PostSwapRequest<T> postSwapRequest,
                                                           @NonNull MintLoadService mintLoadService) throws CashuErrorException {

        // Load mint
        Mint mint = mintLoadService.load(mintId, false);

        if (mint == null) {
            throw new CashuErrorException("swap_mint_not_found");
        }

        // Verify proofs
        new VerifyProofsTask(mint, postSwapRequest, MintProtocolServiceFactory.getInstance()).execute();

        // Invalidate proofs
        new InvalidateProofsTask(mint, postSwapRequest.getInputs()).execute();

        // Issue new signatures
        List<BlindSignature> blindSignatures = new ArrayList<>();
        List<BlindedMessage> blindedMessages = postSwapRequest.getBlindedMessages();
        blindedMessages.forEach(bm -> {
            BlindSignature blindSignature  = new SignBlindedMessageTask(mint, bm, MintProtocolServiceFactory.getInstance()).execute();
            blindSignatures.add(blindSignature);
        });

        // Sort blindSignatures by the amount in ascending order
        // TODO: This is a temporary solution for now. Ultimately, we may need to ensure that the blind signatures are sorted in ascending order
        // blindSignatures.sort(Comparator.comparing(blindSignature -> blindSignature.getAmount()));

        PostSwapResponse postSwapResponse = new PostSwapResponse(blindSignatures);

        // Verify fees
        new VerifyFeesTask(postSwapRequest, postSwapResponse).execute();

        return postSwapResponse;
    }

}