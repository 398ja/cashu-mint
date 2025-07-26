package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.entities.rest.PostCheckStateRequest;
import xyz.tcheeric.cashu.entities.rest.PostCheckStateResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.service.DefaultProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.DefaultMintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.tasks.CheckStateTask;

import java.util.UUID;

@Nut(value = 7, description = "Check the state of a proof")
public class NUT07 {

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
