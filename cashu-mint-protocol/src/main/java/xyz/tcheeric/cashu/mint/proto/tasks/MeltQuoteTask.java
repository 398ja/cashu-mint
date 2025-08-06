package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.entities.rest.PostMeltQuoteRequest;
import xyz.tcheeric.cashu.entities.rest.PostMeltQuoteResponse;
import xyz.tcheeric.cashu.gateway.Gateway;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.util.FeeConfig;

/**
 * Task used for generating melt quotes via the configured payment gateway.
 */
public class MeltQuoteTask implements Task<PostMeltQuoteResponse> {

    private final PostMeltQuoteRequest request;
    private final PaymentMethod method;
    private final MintProtocolService mintProtocolService;

    public MeltQuoteTask(@NonNull PostMeltQuoteRequest request, @NonNull PaymentMethod method) {
        this(request, method, MintProtocolServiceFactory.getInstance());
    }

    public MeltQuoteTask(@NonNull PostMeltQuoteRequest request,
                         @NonNull PaymentMethod method,
                         @NonNull MintProtocolService mintProtocolService) {
        this.request = request;
        this.method = method;
        this.mintProtocolService = mintProtocolService;
    }

    @Override
    public PostMeltQuoteResponse execute() throws CashuErrorException {
        Gateway gateway = mintProtocolService.createGateway(method);
        String quoteId = gateway.createMeltQuote(request.getRequest());
        int feeReserve = gateway.getFeeReserve(quoteId);
        Integer expiry = gateway.getPaymentExpiry(quoteId);
        int amount = gateway.getAmount(quoteId);
        feeReserve += (int) Math.ceil(amount * FeeConfig.getFeeReservePercent());

        return PostMeltQuoteResponse.builder()
                .quoteId(quoteId)
                .feeReserve(feeReserve)
                .expiry(expiry)
                .amount(amount)
                .build();
    }
}
