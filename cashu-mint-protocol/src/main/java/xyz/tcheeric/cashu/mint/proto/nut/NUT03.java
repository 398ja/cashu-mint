package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapResponse;
import xyz.tcheeric.cashu.mint.proto.tasks.SwapTask;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;

import java.util.UUID;

/**
 * NUT-03: Swap tokens.
 *
 * <p>This class provides static methods for swapping existing proofs for new
 * blinded signatures. Swaps atomically invalidate input proofs and issue new outputs.
 *
 * <p><b>Security:</b> Input proofs are verified cryptographically before invalidation.
 * Per-proof locking prevents double-spend attacks during concurrent swap operations.
 * The total input amount must equal the total output amount (minus fees if applicable).
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/03.md">NUT-03 Specification</a>
 */
@Slf4j
@Nut(3)
public final class NUT03 {

    private NUT03() {
        // Utility class - prevent instantiation
    }

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