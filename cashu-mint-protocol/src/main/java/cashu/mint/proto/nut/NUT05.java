package cashu.mint.proto.nut;

import cashu.common.model.Mint;
import cashu.common.model.PaymentMethod;
import cashu.common.model.rest.PostMeltQuoteRequest;
import cashu.common.model.rest.PostMeltQuoteResponse;
import cashu.common.model.rest.PostMeltRequest;
import cashu.common.model.rest.PostMeltResponse;
import cashu.common.util.CashuErrorException;
import cashu.gateway.Gateway;
import cashu.mint.proto.tasks.MeltTask;
import cashu.vault.impl.fs.FSMintVault;
import lombok.NonNull;
import lombok.extern.java.Log;

import static cashu.mint.proto.util.MintUtil.createGateway;

@Log
public class NUT05 {

    public static PostMeltQuoteResponse quote(@NonNull PostMeltQuoteRequest postMeltQuoteRequest, @NonNull PaymentMethod method) {
        var gateway = createGateway(method);
        var quoteId = gateway.createMeltQuote(postMeltQuoteRequest.getRequest());
        var feeReserve = gateway.getFeeReserve(quoteId);
        var expiry = gateway.getPaymentExpiry(quoteId);
        var amount = gateway.getAmount(quoteId);

        return PostMeltQuoteResponse
                .builder()
                .quoteId(quoteId)
                .feeReserve(feeReserve)
                .expiry(expiry) // TODO - check if this is correct
                .amount(amount)
                .build();
    }

    public static PostMeltQuoteResponse quotePaymentStatus(@NonNull String quoteId, @NonNull PaymentMethod method) {
        Gateway gateway = createGateway(method);
        return PostMeltQuoteResponse
                .builder()
                .quoteId(quoteId)
                .expiry(gateway.getPaymentExpiry(quoteId))
                .paid(gateway.checkPaymentStatus(quoteId))
                .build();
    }

    public static PostMeltResponse melt(@NonNull PostMeltRequest request, @NonNull PaymentMethod method) throws CashuErrorException {
        Mint mint = FSMintVault.load(false, true);

        return new MeltTask(request, method, mint).execute();
    }

}
