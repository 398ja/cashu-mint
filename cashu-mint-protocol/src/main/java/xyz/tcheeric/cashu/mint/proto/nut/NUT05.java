package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import lombok.extern.java.Log;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.entities.rest.PostMeltQuoteRequest;
import xyz.tcheeric.cashu.entities.rest.PostMeltQuoteResponse;
import xyz.tcheeric.cashu.entities.rest.PostMeltRequest;
import xyz.tcheeric.cashu.entities.rest.PostMeltResponse;
import xyz.tcheeric.cashu.gateway.Gateway;
import xyz.tcheeric.cashu.mint.proto.tasks.MeltTask;
import xyz.tcheeric.cashu.vault.impl.fs.FSMintVault;

import static xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil.createGateway;

@Log
@Nut(value = 5, description = "Melt tokens")
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

    public static <T extends Secret> PostMeltResponse melt(@NonNull PostMeltRequest<T> request, @NonNull PaymentMethod method) throws CashuErrorException {
        Mint mint = FSMintVault.load(false, true);

        return new MeltTask(request, method, mint).execute();
    }

}
