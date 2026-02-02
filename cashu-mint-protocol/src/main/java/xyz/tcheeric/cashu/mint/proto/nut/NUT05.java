package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.entities.rest.PostMeltQuoteRequest;
import xyz.tcheeric.cashu.entities.rest.PostMeltQuoteResponse;
import xyz.tcheeric.cashu.entities.rest.PostMeltRequest;
import xyz.tcheeric.cashu.entities.rest.PostMeltResponse;
import xyz.tcheeric.cashu.mint.proto.tasks.MeltTokensTask;
import xyz.tcheeric.cashu.mint.proto.tasks.MeltQuoteTask;
import xyz.tcheeric.cashu.mint.proto.tasks.MeltQuoteStatusTask;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;

import java.util.UUID;


/**
 * NUT-05: Melt tokens.
 *
 * <p>This class provides static methods for creating melt quotes and melting
 * tokens to pay Lightning invoices.
 *
 * <p><b>Security:</b> Input proofs are cryptographically verified before spending.
 * Per-proof locking prevents double-spend attacks during concurrent melt operations.
 * Proofs are marked as pending during payment and invalidated upon success.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/05.md">NUT-05 Specification</a>
 */
@Slf4j
@Nut(value = 5, description = "Melt tokens")
public final class NUT05 {

    private NUT05() {
        // Utility class - prevent instantiation
    }

    public static PostMeltQuoteResponse quote(@NonNull PostMeltQuoteRequest postMeltQuoteRequest, @NonNull PaymentMethod method) {
        return quote(postMeltQuoteRequest, method, null, MintProtocolServiceFactory.getInstance());
    }

    public static PostMeltQuoteResponse quote(@NonNull PostMeltQuoteRequest postMeltQuoteRequest,
                                              @NonNull PaymentMethod method,
                                              String unit,
                                              @NonNull MintProtocolService mintProtocolService) {
        try {
            return new MeltQuoteTask(postMeltQuoteRequest, method, unit, mintProtocolService).execute();
        } catch (CashuErrorException e) {
            throw new RuntimeException(e);
        }
    }

    public static PostMeltQuoteResponse quotePaymentStatus(@NonNull String quoteId, @NonNull PaymentMethod method) {
        return quotePaymentStatus(quoteId, method, null, MintProtocolServiceFactory.getInstance());
    }

    public static PostMeltQuoteResponse quotePaymentStatus(@NonNull String quoteId,
                                                           @NonNull PaymentMethod method,
                                                           String unit,
                                                           @NonNull MintProtocolService mintProtocolService) {
        try {
            return new MeltQuoteStatusTask(quoteId, method, unit, mintProtocolService).execute();
        } catch (CashuErrorException e) {
            throw new RuntimeException(e);
        }
    }

    public static <T extends Secret> PostMeltResponse melt(@NonNull UUID mintId,
                                                           @NonNull PostMeltRequest<T> request,
                                                           @NonNull PaymentMethod method) throws CashuErrorException {
        return melt(mintId, request, method, null,
                MintProtocolServiceFactory.getInstance(),
                new DefaultMintLoadService(),
                new DefaultMintVaultService(),
                new DefaultProofVaultService());
    }

    public static <T extends Secret> PostMeltResponse melt(@NonNull UUID mintId,
                                                           @NonNull PostMeltRequest<T> request,
                                                           @NonNull PaymentMethod method,
                                                           String unit) throws CashuErrorException {
        return melt(mintId, request, method, unit,
                MintProtocolServiceFactory.getInstance(),
                new DefaultMintLoadService(),
                new DefaultMintVaultService(),
                new DefaultProofVaultService());
    }

    public static <T extends Secret> PostMeltResponse melt(@NonNull UUID mintId,
                                                           @NonNull PostMeltRequest<T> request,
                                                           @NonNull PaymentMethod method,
                                                           String unit,
                                                           @NonNull MintProtocolService mintProtocolService,
                                                           @NonNull MintLoadService mintLoadService,
                                                           @NonNull MintVaultService mintVaultService,
                                                           @NonNull ProofVaultService proofVaultService) throws CashuErrorException {
        return new MeltTokensTask<>(
                mintId,
                request,
                method,
                unit,
                mintProtocolService,
                mintLoadService,
                mintVaultService,
                proofVaultService
        ).execute();
    }

    public static <T extends Secret> PostMeltResponse melt(@NonNull UUID mintId,
                                                           @NonNull PostMeltRequest<T> request,
                                                           @NonNull PaymentMethod method,
                                                           @NonNull MintVaultService mintVaultService,
                                                           @NonNull ProofVaultService proofVaultService) throws CashuErrorException {
        return melt(mintId, request, method, null,
                MintProtocolServiceFactory.getInstance(),
                new DefaultMintLoadService(),
                mintVaultService,
                proofVaultService);
    }

}
