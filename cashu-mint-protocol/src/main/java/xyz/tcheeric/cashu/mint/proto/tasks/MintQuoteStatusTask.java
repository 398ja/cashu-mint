package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuote;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

/**
 * Task for retrieving the status of a mint quote. Shared by the regular
 * NUT-04 path ({@code /v1/mint/quote/bolt11/{id}}) and the voucher extension
 * ({@code /v1/mint/quote/voucher/{method}/{id}}), so it resolves quote data
 * from whichever durable repository holds the row.
 */
public class MintQuoteStatusTask extends InstrumentedTask<PostMintQuoteResponse> {

    /** Default unit emitted when no durable row pins one (v1 clients reject blank). */
    private static final String DEFAULT_UNIT = "sat";

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
        // Treat blank/whitespace unit the same as absent so we never select a
        // gateway with an invalid unit string (consistent with creation).
        String requestedUnit = (unit == null || unit.isBlank()) ? null : unit;
        Gateway gateway = requestedUnit == null ? mintProtocolService.createGateway(method)
                : mintProtocolService.createGateway(method, requestedUnit);
        boolean paid = gateway.checkPaymentStatus(quoteId);

        // NUT-04 v1 — modern wallets (cashu-ts >= 4.x) normalize amount/unit/state
        // on the status response too, and require the same quote shape as creation.
        // Resolve from the durable row (regular OR voucher repository); fall back to
        // the payment flag when no row is found (e.g. unit tests) so the v0 shape
        // still round-trips.
        ResolvedQuote resolved = resolveQuote(paid);
        String resolvedUnit = (requestedUnit != null) ? requestedUnit
                : (resolved.unit != null && !resolved.unit.isBlank() ? resolved.unit : DEFAULT_UNIT);

        return PostMintQuoteResponse.builder()
                .quoteId(quoteId)
                .request(gateway.getRequest(quoteId))
                .amount(clampToInt(resolved.amount()))
                .unit(resolvedUnit)
                .state(resolved.state)
                .paid(paid)
                .expiry(gateway.getPaymentExpiry(quoteId))
                .build();
    }

    /**
     * Resolved v1 fields, sourced from whichever durable repository holds the
     * row. {@code amount} stays {@code long} (spec 001 FR-009 — amount-bearing
     * fields must not be {@code int}); it is clamped to the DTO's {@code int}
     * field only at the wire boundary.
     */
    private record ResolvedQuote(long amount, String unit, String state) {
    }

    private ResolvedQuote resolveQuote(boolean paid) {
        MintQuoteRepository regular = MintIntegrityContext.quoteRepository();
        if (regular != null) {
            MintQuote q = regular.findById(quoteId).orElse(null);
            if (q != null) {
                return new ResolvedQuote(q.amount(), q.unit(), regularState(q.lifecycleState(), paid));
            }
        }

        // Voucher quotes live in a separate repository; the regular row will be
        // absent for voucher quote IDs (Codex P2).
        VoucherQuoteRepository voucherRepo = MintIntegrityContext.voucherQuoteRepository();
        if (voucherRepo != null) {
            VoucherQuote vq = voucherRepo.findById(quoteId).orElse(null);
            if (vq != null) {
                // The mintable amount is the FACE VALUE (matches VoucherMintQuoteTask).
                return new ResolvedQuote(vq.faceValue(), vq.unit(), voucherState(vq.lifecycleState(), paid));
            }
        }

        return new ResolvedQuote(0L, null, paid ? LifecycleState.PAID.name() : LifecycleState.UNPAID.name());
    }

    /**
     * Map the durable regular lifecycle (or, absent a row, the payment flag) to
     * the three NUT-04 v1 wire states a wallet understands: {@code UNPAID},
     * {@code PAID}, {@code ISSUED}.
     */
    private static String regularState(LifecycleState lifecycle, boolean paid) {
        if (lifecycle == null) {
            return paid ? LifecycleState.PAID.name() : LifecycleState.UNPAID.name();
        }
        return switch (lifecycle) {
            case ISSUED -> LifecycleState.ISSUED.name();
            case PAID, ISSUING -> LifecycleState.PAID.name();
            default -> paid ? LifecycleState.PAID.name() : LifecycleState.UNPAID.name();
        };
    }

    /** Map the voucher lifecycle onto the same three NUT-04 v1 wire states. */
    private static String voucherState(VoucherLifecycleState lifecycle, boolean paid) {
        if (lifecycle == null) {
            return paid ? LifecycleState.PAID.name() : LifecycleState.UNPAID.name();
        }
        return switch (lifecycle) {
            case ISSUED -> LifecycleState.ISSUED.name();
            case FUNDED, ISSUING -> LifecycleState.PAID.name();
            default -> paid ? LifecycleState.PAID.name() : LifecycleState.UNPAID.name();
        };
    }

    /** Clamp a durable {@code long} amount into the v1 {@code int} field, guarding overflow. */
    private static int clampToInt(long value) {
        if (value <= 0) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
