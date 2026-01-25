package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.PostMintQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

/**
 * Task for creating a mint quote.
 */
public class MintQuoteTask extends InstrumentedTask<PostMintQuoteResponse> {

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

    // Backward-compatible constructor used by tests: no unit parameter
    public MintQuoteTask(int amount,
                         @NonNull PaymentMethod method,
                         @NonNull MintProtocolService mintProtocolService) {
        this(amount, method, null, mintProtocolService);
    }

    @Override
    protected PostMintQuoteResponse doExecute() throws CashuErrorException {
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
