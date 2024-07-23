package cashu.mint.proto.nut;

import cashu.common.annotation.Nut;
import cashu.common.model.Mint;
import cashu.common.model.PaymentMethod;
import cashu.common.model.rest.PostMintQuoteResponse;
import cashu.common.model.rest.PostMintRequest;
import cashu.common.model.rest.PostMintResponse;
import cashu.common.protocol.CashuErrorException;
import cashu.gateway.Gateway;
import cashu.mint.proto.tasks.MintTask;
import cashu.vault.impl.fs.FSMintVault;
import lombok.NonNull;
import lombok.extern.java.Log;

import java.util.UUID;

import static cashu.mint.proto.util.MintUtil.createGateway;

@Nut(value = 4, description = "Mint tokens")
@Log
public class NUT04 {

    public static PostMintQuoteResponse quote(int amount, @NonNull PaymentMethod method) {

        var gateway = createGateway(method, "mint");
        var quoteId = UUID.randomUUID();
        var request = gateway.createRequest(amount);
        var expiry = gateway.getPaymentExpiry(quoteId.toString());

        return PostMintQuoteResponse.builder()
                .quoteId(quoteId.toString())
                .request(request)
                .expiry(expiry) // TODO - check if this is correct
                .build();
    }

    public static PostMintQuoteResponse quotePaymentStatus(@NonNull String quoteId, @NonNull PaymentMethod method) {
        Gateway gateway = createGateway(method, "mint");
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
