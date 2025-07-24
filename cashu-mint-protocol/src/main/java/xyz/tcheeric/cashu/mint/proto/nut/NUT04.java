package xyz.tcheeric.cashu.mint.proto.nut;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.annotation.Nut;
import xyz.tcheeric.cashu.entities.rest.PostMintQuoteResponse;
import xyz.tcheeric.cashu.entities.rest.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.PostMintResponse;
import xyz.tcheeric.cashu.gateway.Gateway;
import xyz.tcheeric.cashu.mint.proto.tasks.MintTask;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.vault.api.db.impl.DBMintVault;

import java.util.UUID;

import static xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil.createGateway;

@Nut(value = 4, description = "Mint tokens")
@Slf4j
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

    public static <T extends Secret> PostMintResponse mint(@NonNull UUID mintId, @NonNull PostMintRequest<T> postMintRequest, @NonNull PaymentMethod method) throws CashuErrorException {
        Mint mint = DBMintVault.load(mintId, false);
        return new MintTask(postMintRequest, method, mint, MintProtocolServiceFactory.getInstance()).execute();
    }

}
