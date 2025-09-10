package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.entities.rest.PostMintQuoteResponse;
import xyz.tcheeric.cashu.entities.rest.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.PostMintResponse;
import xyz.tcheeric.cashu.mint.proto.tasks.MintQuoteStatusTask;
import xyz.tcheeric.cashu.mint.proto.tasks.MintQuoteTask;
import xyz.tcheeric.cashu.mint.proto.tasks.MintTokensTask;
import xyz.tcheeric.cashu.mint.proto.service.DefaultMintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;

import java.util.UUID;

@Nut(value = 4, description = "Mint tokens")
@Slf4j
public class NUT04 {

    public static PostMintQuoteResponse quote(int amount, @NonNull PaymentMethod method) {
        return new MintQuoteTask(amount, method).execute();
    }

    public static PostMintQuoteResponse quotePaymentStatus(@NonNull String quoteId, @NonNull PaymentMethod method) {
        return new MintQuoteStatusTask(quoteId, method).execute();
    }

    public static <T extends Secret> PostMintResponse mint(@NonNull UUID mintId,
                                                           @NonNull PostMintRequest<T> postMintRequest,
                                                           @NonNull PaymentMethod method,
                                                           @NonNull SignatureVaultService signatureVaultService) throws CashuErrorException {
        return mint(mintId, postMintRequest, method,
                new DefaultMintLoadService(),
                MintProtocolServiceFactory.getInstance(),
                signatureVaultService);
    }

    public static <T extends Secret> PostMintResponse mint(@NonNull UUID mintId,
                                                           @NonNull PostMintRequest<T> postMintRequest,
                                                           @NonNull PaymentMethod method,
                                                           @NonNull MintLoadService mintLoadService,
                                                           @NonNull MintProtocolService mintProtocolService,
                                                           @NonNull SignatureVaultService signatureVaultService) throws CashuErrorException {
        return new MintTokensTask<>(mintId, postMintRequest, method, mintLoadService, mintProtocolService, signatureVaultService).execute();
    }

}
