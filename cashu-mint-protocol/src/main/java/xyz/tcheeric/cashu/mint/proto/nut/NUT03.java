package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.PostSwapResponse;
import xyz.tcheeric.cashu.mint.proto.tasks.SwapTask;
import xyz.tcheeric.cashu.mint.proto.service.DefaultMintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;

import java.util.UUID;

// TEST - When calling swap, ensure that the VerifyProofs and InvalidateProofs tasks are executed
@Slf4j
@Nut(3)
public class NUT03 {

    public static <T extends Secret> PostSwapResponse swap(@NonNull UUID mintId,
                                                           @NonNull PostSwapRequest<T> postSwapRequest,
                                                           @NonNull SignatureVaultService signatureVaultService) throws CashuErrorException {
        return swap(mintId, postSwapRequest, new DefaultMintLoadService(), signatureVaultService);
    }

    public static <T extends Secret> PostSwapResponse swap(@NonNull UUID mintId,
                                                           @NonNull PostSwapRequest<T> postSwapRequest,
                                                           @NonNull MintLoadService mintLoadService,
                                                           @NonNull SignatureVaultService signatureVaultService) throws CashuErrorException {
        return new SwapTask<>(mintId, postSwapRequest, mintLoadService, signatureVaultService).execute();
    }

}