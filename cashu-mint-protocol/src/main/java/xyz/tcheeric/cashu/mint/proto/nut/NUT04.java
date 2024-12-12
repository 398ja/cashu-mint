package xyz.tcheeric.cashu.mint.proto.nut;

import xyz.tcheeric.cashu.common.annotation.Nut;
import xyz.tcheeric.cashu.common.model.Mint;
import xyz.tcheeric.cashu.common.model.PaymentMethod;
import xyz.tcheeric.cashu.common.model.rest.PostMintQuoteResponse;
import xyz.tcheeric.cashu.common.model.rest.PostMintRequest;
import xyz.tcheeric.cashu.common.model.rest.PostMintResponse;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.gateway.Gateway;
import xyz.tcheeric.cashu.mint.proto.tasks.MintTask;
import xyz.tcheeric.cashu.vault.impl.fs.FSMintVault;
import lombok.NonNull;
import lombok.extern.java.Log;


import static xyz.tcheeric.cashu.mint.proto.util.MintUtil.createGateway;

@Nut(value = 4, description = "Mint tokens")
@Log
public class NUT04 {

    public static PostMintQuoteResponse quote(int amount, @NonNull PaymentMethod method) {
        var gateway = createGateway(method);
        var quoteId = gateway.createMintQuote(amount, null);
        var request = gateway.getRequest(quoteId);
        var expiry = gateway.getPaymentExpiry(quoteId);

        return PostMintQuoteResponse.builder()
                .quoteId(quoteId)
                .request(request)
                .expiry(expiry) // TODO - check if this is correct
                .build();
    }

    public static PostMintQuoteResponse quotePaymentStatus(@NonNull String quoteId, @NonNull PaymentMethod method) {
        Gateway gateway = createGateway(method);
        return PostMintQuoteResponse
                .builder()
                .quoteId(quoteId)
                .request(gateway.getRequest(quoteId))
                .expiry(gateway.getPaymentExpiry(quoteId))
                .paid(gateway.checkPaymentStatus(quoteId))
                .build();
    }

    public static PostMintResponse mint(@NonNull PostMintRequest postMintRequest, @NonNull PaymentMethod method) throws CashuErrorException {
        Mint mint = FSMintVault.load(false, false);
        return new MintTask(postMintRequest, method, mint).execute();
    }

}
