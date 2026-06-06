package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuoteRepository;
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
        boolean paid = gateway.checkPaymentStatus(quoteId);

        // NUT-04 v1 — modern wallets (cashu-ts >= 4.x) normalize amount/unit/state
        // on the status response too. Pull amount/unit/lifecycle from the durable
        // quote row; fall back to the payment flag when no repository is wired
        // (e.g. unit tests) so the v0 shape still round-trips.
        MintQuote quote = loadQuote(quoteId);
        int amount = quote != null ? (int) quote.amount() : 0;
        String resolvedUnit = unit != null ? unit : (quote != null ? quote.unit() : null);
        String state = resolveState(quote, paid);

        return PostMintQuoteResponse.builder()
                .quoteId(quoteId)
                .request(gateway.getRequest(quoteId))
                .amount(amount)
                .unit(resolvedUnit)
                .state(state)
                .paid(paid)
                .expiry(gateway.getPaymentExpiry(quoteId))
                .build();
    }

    private static MintQuote loadQuote(String quoteId) {
        MintQuoteRepository repo = MintIntegrityContext.quoteRepository();
        if (repo == null) {
            return null;
        }
        return repo.findById(quoteId).orElse(null);
    }

    /**
     * Map the durable lifecycle (or, absent a repository, the payment flag) to
     * the three NUT-04 v1 wire states a wallet understands: {@code UNPAID},
     * {@code PAID}, {@code ISSUED}.
     */
    private static String resolveState(MintQuote quote, boolean paid) {
        LifecycleState lifecycle = quote != null ? quote.lifecycleState() : null;
        if (lifecycle == null) {
            return paid ? LifecycleState.PAID.name() : LifecycleState.UNPAID.name();
        }
        return switch (lifecycle) {
            case ISSUED -> LifecycleState.ISSUED.name();
            case PAID, ISSUING -> LifecycleState.PAID.name();
            default -> paid ? LifecycleState.PAID.name() : LifecycleState.UNPAID.name();
        };
    }
}
