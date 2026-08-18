package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintResponse;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.tasks.MintQuoteStatusTask;
import xyz.tcheeric.cashu.mint.proto.tasks.MintQuoteTask;
import xyz.tcheeric.cashu.mint.proto.tasks.MintTokensTask;
import xyz.tcheeric.cashu.mint.proto.tasks.VoucherMintQuoteTask;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;

import java.util.UUID;

/**
 * NUT-04: Mint tokens.
 *
 * <p>This class provides static methods for creating mint quotes and minting
 * new tokens after Lightning payment verification.
 *
 * <p><b>Security:</b> Minting requires proof of Lightning payment via gateway
 * verification. Per-quote locking prevents double-mint attacks where concurrent
 * requests could both pass payment verification simultaneously.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/04.md">NUT-04 Specification</a>
 */
@Nut(value = 4, description = "Mint tokens")
@Slf4j
public final class NUT04 {

    private NUT04() {
        // Utility class - prevent instantiation
    }

    public static PostMintQuoteResponse quote(int amount, @NonNull PaymentMethod method) throws CashuErrorException {
        return new MintQuoteTask(amount, method, null,
                MintProtocolServiceFactory.getInstance(),
                MintIntegrityContext.quoteRepository(),
                MintIntegrityContext.mintUrl()).execute();
    }

    public static PostMintQuoteResponse quote(int amount, @NonNull PaymentMethod method, String unit) throws CashuErrorException {
        return new MintQuoteTask(amount, method, unit,
                MintProtocolServiceFactory.getInstance(),
                MintIntegrityContext.quoteRepository(),
                MintIntegrityContext.mintUrl()).execute();
    }

    public static PostMintQuoteResponse quotePaymentStatus(@NonNull String quoteId, @NonNull PaymentMethod method) throws CashuErrorException {
        return new MintQuoteStatusTask(quoteId, method).execute();
    }

    public static PostMintQuoteResponse quotePaymentStatus(@NonNull String quoteId, @NonNull PaymentMethod method, String unit) throws CashuErrorException {
        return new MintQuoteStatusTask(quoteId, method, unit, MintProtocolServiceFactory.getInstance()).execute();
    }

    public static <T extends Secret> PostMintResponse mint(@NonNull UUID mintId,
                                                           @NonNull PostMintRequest<T> postMintRequest,
                                                           @NonNull PaymentMethod method,
                                                           @NonNull SignatureVaultService signatureVaultService) throws CashuErrorException {
        return mint(mintId, postMintRequest, method, null,
                new DefaultMintLoadService(),
                MintProtocolServiceFactory.getInstance(),
                signatureVaultService);
    }

    public static <T extends Secret> PostMintResponse mint(@NonNull UUID mintId,
                                                           @NonNull PostMintRequest<T> postMintRequest,
                                                           @NonNull PaymentMethod method,
                                                           String unit,
                                                           @NonNull MintLoadService mintLoadService,
                                                           @NonNull MintProtocolService mintProtocolService,
                                                           @NonNull SignatureVaultService signatureVaultService) throws CashuErrorException {
        return new MintTokensTask<>(mintId, postMintRequest, method, unit, mintLoadService, mintProtocolService,
                signatureVaultService,
                MintIntegrityContext.quoteRepository(),
                MintIntegrityContext.issuanceRecordRepository()).execute();
    }

    /**
     * Create a voucher mint quote with percentage-based fee.
     *
     * <p>Unlike regular mint quotes, voucher quotes charge only a percentage
     * of the face value (configured via {@code voucher.quote.fee-percent}).
     *
     * @param amount the voucher face value in satoshis
     * @param method the payment method
     * @return the mint quote response with fee-based invoice
     */
    public static PostMintQuoteResponse quoteVoucher(int amount, @NonNull PaymentMethod method) throws CashuErrorException {
        return new VoucherMintQuoteTask(amount, method).execute();
    }

    /**
     * Create a voucher mint quote with percentage-based fee and specific unit.
     *
     * @param amount the voucher face value in satoshis
     * @param method the payment method
     * @param unit   the unit (e.g., "sat", "usd")
     * @return the mint quote response with fee-based invoice
     */
    public static PostMintQuoteResponse quoteVoucher(int amount, @NonNull PaymentMethod method, String unit) throws CashuErrorException {
        return new VoucherMintQuoteTask(amount, method, unit, MintProtocolServiceFactory.getInstance()).execute();
    }

    /**
     * Check payment status for a voucher mint quote.
     *
     * <p>Uses the same status check as regular mint quotes.
     *
     * @param quoteId the quote identifier
     * @param method  the payment method
     * @return the quote status response
     */
    public static PostMintQuoteResponse voucherQuotePaymentStatus(@NonNull String quoteId, @NonNull PaymentMethod method) throws CashuErrorException {
        return new MintQuoteStatusTask(quoteId, method).execute();
    }

    /**
     * Check payment status for a voucher mint quote with specific unit.
     *
     * @param quoteId the quote identifier
     * @param method  the payment method
     * @param unit    the unit (e.g., "sat", "usd")
     * @return the quote status response
     */
    public static PostMintQuoteResponse voucherQuotePaymentStatus(@NonNull String quoteId, @NonNull PaymentMethod method, String unit) throws CashuErrorException {
        return new MintQuoteStatusTask(quoteId, method, unit, MintProtocolServiceFactory.getInstance()).execute();
    }

}
