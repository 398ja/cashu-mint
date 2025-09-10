package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.entities.rest.PostMintQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolServiceFactory;
import xyz.tcheeric.gateway.common.Gateway;

/**
 * Task for creating a mint quote.
 */
public class MintQuoteTask implements Task<PostMintQuoteResponse> {

    private final int amount;
    private final PaymentMethod method;
    private final String unit;
    private final MintProtocolService mintProtocolService;

    public MintQuoteTask(int amount, @NonNull PaymentMethod method) {
        this(amount, method, null, MintProtocolServiceFactory.getInstance());
    }

    public MintQuoteTask(int amount,
                         @NonNull PaymentMethod method,
                         String unit,
                         @NonNull MintProtocolService mintProtocolService) {
        this.amount = amount;
        this.method = method;
        this.unit = unit;
        this.mintProtocolService = mintProtocolService;
    }

    @Override
    public PostMintQuoteResponse execute() {
        Gateway gateway = unit == null ? mintProtocolService.createGateway(method)
                : mintProtocolService.createGateway(method, unit);
        String quoteId = gateway.createMintQuote(amount, null);
        String request = gateway.getRequest(quoteId);
        Integer expiry = gateway.getPaymentExpiry(quoteId);

        return PostMintQuoteResponse.builder()
                .quoteId(quoteId)
                .request(request)
                .expiry(expiry)
                .build();
    }
}
