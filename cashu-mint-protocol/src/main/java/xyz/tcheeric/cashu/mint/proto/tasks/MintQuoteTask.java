package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

/**
 * Task for creating a mint quote.
 */
public class MintQuoteTask extends InstrumentedTask<PostMintQuoteResponse> {

    private final long amount;
    private final PaymentMethod method;
    private final String unit;
    private final MintProtocolService mintProtocolService;

    public MintQuoteTask(long amount, @NonNull PaymentMethod method) {
        this(amount, method, null, MintProtocolServiceFactory.getInstance());
    }

    // Backward-compatible int constructor; widens to long internally.
    public MintQuoteTask(int amount, @NonNull PaymentMethod method) {
        this((long) amount, method);
    }

    public MintQuoteTask(long amount,
                         @NonNull PaymentMethod method,
                         String unit,
                         @NonNull MintProtocolService mintProtocolService) {
        this.amount = amount;
        this.method = method;
        this.unit = unit;
        this.mintProtocolService = mintProtocolService;
    }

    // Backward-compatible constructor used by tests: no unit parameter
    public MintQuoteTask(long amount,
                         @NonNull PaymentMethod method,
                         @NonNull MintProtocolService mintProtocolService) {
        this(amount, method, null, mintProtocolService);
    }

    @Override
    protected PostMintQuoteResponse doExecute() throws CashuErrorException {
        Gateway gateway = unit == null ? mintProtocolService.createGateway(method)
                : mintProtocolService.createGateway(method, unit);
        if (amount > Integer.MAX_VALUE || amount <= 0) {
            throw new CashuErrorException("invalid_quote_amount");
        }
        // Boundary cast: payment-adapter Gateway#createMintQuote still takes Integer.
        // Tracked cross-repo per spec 001 research R6 (Gateway interface migration).
        String quoteId = gateway.createMintQuote((int) amount, null);
        String request = gateway.getRequest(quoteId);
        Integer expiry = gateway.getPaymentExpiry(quoteId);

        return PostMintQuoteResponse.builder()
                .quoteId(quoteId)
                .request(request)
                .expiry(expiry)
                .build();
    }
}
