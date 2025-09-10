package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.entities.rest.PostMintQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolServiceFactory;
import xyz.tcheeric.gateway.common.Gateway;

/**
 * Task for retrieving the status of a mint quote.
 */
public class MintQuoteStatusTask implements Task<PostMintQuoteResponse> {

    private final String quoteId;
    private final PaymentMethod method;
    private final String unit;
    private final MintProtocolService mintProtocolService;

    public MintQuoteStatusTask(@NonNull String quoteId, @NonNull PaymentMethod method) {
        this(quoteId, method, null, MintProtocolServiceFactory.getInstance());
    }

    public MintQuoteStatusTask(@NonNull String quoteId,
                               @NonNull PaymentMethod method,
                               String unit,
                               @NonNull MintProtocolService mintProtocolService) {
        this.quoteId = quoteId;
        this.method = method;
        this.unit = unit;
        this.mintProtocolService = mintProtocolService;
    }

    @Override
    public PostMintQuoteResponse execute() {
        Gateway gateway = unit == null ? mintProtocolService.createGateway(method)
                : mintProtocolService.createGateway(method, unit);
        return PostMintQuoteResponse.builder()
                .quoteId(quoteId)
                .request(gateway.getRequest(quoteId))
                .expiry(gateway.getPaymentExpiry(quoteId))
                .paid(gateway.checkPaymentStatus(quoteId))
                .build();
    }
}
