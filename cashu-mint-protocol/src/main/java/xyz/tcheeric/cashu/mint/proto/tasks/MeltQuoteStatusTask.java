package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.entities.rest.PostMeltQuoteResponse;
import xyz.tcheeric.cashu.gateway.Gateway;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolServiceFactory;

/**
 * Task used for retrieving the status of a melt quote.
 */
public class MeltQuoteStatusTask implements Task<PostMeltQuoteResponse> {

    private final String quoteId;
    private final PaymentMethod method;
    private final MintProtocolService mintProtocolService;

    public MeltQuoteStatusTask(@NonNull String quoteId, @NonNull PaymentMethod method) {
        this(quoteId, method, MintProtocolServiceFactory.getInstance());
    }

    public MeltQuoteStatusTask(@NonNull String quoteId,
                               @NonNull PaymentMethod method,
                               @NonNull MintProtocolService mintProtocolService) {
        this.quoteId = quoteId;
        this.method = method;
        this.mintProtocolService = mintProtocolService;
    }

    @Override
    public PostMeltQuoteResponse execute() throws CashuErrorException {
        Gateway gateway = mintProtocolService.createGateway(method);
        return PostMeltQuoteResponse.builder()
                .quoteId(quoteId)
                .expiry(gateway.getPaymentExpiry(quoteId))
                .paid(gateway.checkPaymentStatus(quoteId))
                .build();
    }
}
