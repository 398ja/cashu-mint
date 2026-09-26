package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltQuoteRequest;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.util.AmountLimitContext;
import xyz.tcheeric.cashu.mint.proto.util.FeeConfig;
import xyz.tcheeric.cashu.mint.proto.util.MintCapabilityProperties;
import xyz.tcheeric.cashu.mint.proto.util.QuoteExpiry;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

/**
 * NUT-05 melt-quote creation: asks the gateway to decode the invoice and returns
 * the amount, fee reserve and expiry.
 *
 * <p>The resolved invoice amount is checked against the melt limits advertised
 * under NUT-06, so a wallet is never quoted a melt the mint has told it it will
 * not perform (issue #390).
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/05.md">NUT-05</a>
 */
public class MeltQuoteTask extends InstrumentedTask<PostMeltQuoteResponse> {

    private static final String DEFAULT_UNIT = "sat";

    private final PostMeltQuoteRequest request;
    private final PaymentMethod method;
    private final String unit;
    private final MintProtocolService mintProtocolService;

    public MeltQuoteTask(@NonNull PostMeltQuoteRequest request, @NonNull PaymentMethod method) {
        this(request, method, null, MintProtocolServiceFactory.getInstance());
    }

    public MeltQuoteTask(@NonNull PostMeltQuoteRequest request,
                         @NonNull PaymentMethod method,
                         String unit,
                         @NonNull MintProtocolService mintProtocolService) {
        this.request = request;
        this.method = method;
        this.unit = unit;
        this.mintProtocolService = mintProtocolService;
    }

    // Backward-compatible constructor used by tests: no unit parameter
    public MeltQuoteTask(@NonNull PostMeltQuoteRequest request,
                         @NonNull PaymentMethod method,
                         @NonNull MintProtocolService mintProtocolService) {
        this(request, method, null, mintProtocolService);
    }

    /**
     * Returns the unit this quote transacts in, defaulting to the first melt
     * method the mint advertises when the caller did not name one.
     *
     * @return the unit to enforce limits against
     */
    private String resolveUnit() {
        if (unit != null && !unit.isBlank()) {
            return unit;
        }
        return AmountLimitContext.policy().meltMethods().stream()
                .findFirst()
                .map(MintCapabilityProperties.PaymentMethodLimits::getUnit)
                .orElse(DEFAULT_UNIT);
    }

    @Override
    protected PostMeltQuoteResponse doExecute() throws CashuErrorException {
        Gateway gateway = unit == null ? mintProtocolService.createGateway(method)
                : mintProtocolService.createGateway(method, unit);
        String quoteId = gateway.createMeltQuote(request.getRequest());
        int feeReserve = gateway.getFeeReserve(quoteId);
        Integer expiry = gateway.getPaymentExpiry(quoteId);
        int amount = gateway.getAmount(quoteId);
        // Issue #390: the invoice amount only becomes known once the gateway has
        // decoded it, so the advertised NUT-05 range is enforced here rather than
        // on the request, and against the same limits /v1/info publishes.
        AmountLimitContext.policy().requireWithinMeltLimits(amount, resolveUnit());
        feeReserve += (int) Math.ceil(amount * FeeConfig.getFeeReservePercent());

        // `unit` and `request` are not optional. NUT-05 lists both on
        // PostMeltQuoteResponse, and wallets model them as required strings, so
        // omitting them does not degrade the response — it fails the whole
        // thing to deserialise:
        //
        //   ValidationError: 2 validation errors for PostMeltQuoteResponse
        //   unit    Input should be a valid string [input_value=None]
        //   request Input should be a valid string [input_value=None]
        //
        // A wallet therefore cannot melt against this mint at all. Both values
        // were already to hand: resolveUnit() is used two lines above for the
        // limit check, and request.getRequest() is the invoice the quote is
        // for.
        return PostMeltQuoteResponse.builder()
                .quoteId(quoteId)
                .request(request.getRequest())
                .unit(resolveUnit())
                .feeReserve(feeReserve)
                // NUT-05/23: an absolute Unix timestamp, not the gateway's relative TTL (#494).
                .expiry(QuoteExpiry.absolute(expiry, null))
                .amount(amount)
                .build();
    }
}
