package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.entities.rest.PostCheckStateRequest;
import xyz.tcheeric.cashu.entities.rest.PostCheckStateResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.tasks.CheckStateTask;

import java.util.UUID;

/**
 * NUT-07: Token state check.
 *
 * <p>This class provides static methods for checking the spend state of proofs.
 * States are: UNSPENT (available), PENDING (in-flight), SPENT (consumed).
 *
 * <p><b>Security:</b> This endpoint is public and allows checking any proof state.
 * It does not reveal the secret or signature, only the Y-coordinate and state.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/07.md">NUT-07 Specification</a>
 */
@Nut(value = 7, description = "Check the state of a proof")
public final class NUT07 {

    private NUT07() {
        // Utility class - prevent instantiation
    }

    public static final String UNSPENT = "UNSPENT";
    public static final String PENDING = "PENDING";
    public static final String SPENT = "SPENT";


    public static PostCheckStateResponse checkState(@NonNull UUID mintId, @NonNull PostCheckStateRequest postCheckStateRequest) throws CashuErrorException {
        return new CheckStateTask(mintId, postCheckStateRequest).execute();
    }

    public static PostCheckStateResponse checkState(@NonNull UUID mintId,
                                                    @NonNull PostCheckStateRequest postCheckStateRequest,
                                                    @NonNull MintProtocolService mintProtocolService,
                                                    @NonNull ProofVaultService proofVaultService,
                                                    @NonNull MintVaultService mintVaultService) throws CashuErrorException {
        return new CheckStateTask(mintId, postCheckStateRequest, mintProtocolService, proofVaultService, mintVaultService).execute();
    }
}
