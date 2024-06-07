package cashu.mint.nut;

import cashu.common.model.Mint;
import cashu.common.model.PaymentMethod;
import cashu.common.model.rest.PostMeltQuoteRequest;
import cashu.common.model.rest.PostMeltQuoteResponse;
import cashu.common.model.rest.PostMeltRequest;
import cashu.common.model.rest.PostMeltResponse;
import cashu.mint.actor.abilities.tasks.MeltTask;
import cashu.mint.gateway.Gateway;
import cashu.vault.impl.fs.FSMintVault;
import lombok.NonNull;
import lombok.extern.java.Log;

import java.util.UUID;

import static cashu.mint.util.MintUtil.createGateway;

@Log
public class NUT05 {

    public static PostMeltQuoteResponse quote(@NonNull PostMeltQuoteRequest request, @NonNull PaymentMethod method) {
        var gateway = createGateway(method);
        var quoteId = UUID.randomUUID();
        var feeReserve = gateway.getFeeReserve(request.getRequestId());
        var expiry = gateway.getPaymentExpiry(quoteId.toString());
        var amount = gateway.getAmount(quoteId.toString());

        return PostMeltQuoteResponse
                .builder()
                .quoteId(quoteId.toString())
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

    public static PostMeltResponse melt(@NonNull PostMeltRequest request, @NonNull PaymentMethod method) {
        Mint mint = FSMintVault.load(false, true);

        //ThreadUtil.MINT_MELT_LOCK.lock();
        var result = new MeltTask(request, method, mint).execute();
        //ThreadUtil.MINT_MELT_LOCK.unlock();
        return result;
    }

}
