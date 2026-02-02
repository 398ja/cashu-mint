package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

/**
 * Task for retrieving the status of a mint quote.
 */
public class MintQuoteStatusTask extends InstrumentedTask<PostMintQuoteResponse> {

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

    // Backward-compatible constructor used by tests: no unit parameter
    public MintQuoteStatusTask(@NonNull String quoteId,
                               @NonNull PaymentMethod method,
                               @NonNull MintProtocolService mintProtocolService) {
        this(quoteId, method, null, mintProtocolService);
    }

    @Override
    protected PostMintQuoteResponse doExecute() throws CashuErrorException {
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
