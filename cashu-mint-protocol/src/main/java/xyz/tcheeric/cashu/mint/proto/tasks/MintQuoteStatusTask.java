package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherMintQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.ports.IssuanceRecord;
import xyz.tcheeric.cashu.mint.proto.ports.IssuanceRecordRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuote;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.util.QuoteExpiry;
import xyz.tcheeric.cashu.mint.proto.util.VoucherQuoteRegistry;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.time.Instant;
import java.util.Optional;

/**
 * Reports the state of one mint quote, for exactly one of the two quote kinds.
 *
 * <p>The mint keeps regular NUT-04 quotes and voucher quotes in separate tables, and they are
 * priced differently: a regular quote's invoice charges its {@code amount}, while a voucher
 * quote's invoice charges a fee (10% by default) and its {@code amount} is the face value that
 * may be minted. A client reading {@code state=ISSUED, amount=X} cannot tell those apart, so the
 * two routes must not answer for each other's quotes (cashu-mint#494):
 *
 * <ul>
 *   <li>{@link Kind#REGULAR} backs {@code GET /v1/mint/quote/{method}/{id}} and refuses a voucher
 *       quote id with {@code quote_not_found}.</li>
 *   <li>{@link Kind#VOUCHER} backs {@code GET /v1/mint/quote/voucher/{method}/{id}} and refuses a
 *       regular quote id with {@code voucher_quote_not_found}.</li>
 * </ul>
 *
 * <p>Both report the NUT-04 accounting fields, and on both they describe the entitlement: once
 * paid, {@code amount_paid} is what may be minted, so {@code amount_paid - amount_issued} is the
 * mintable amount NUT-04 defines. For a voucher that is the face value, although its invoice
 * charged only the fee. The voucher route therefore answers with a
 * {@link VoucherMintQuoteResponse}, whose {@code charged_amount} carries the price; a verifier
 * comparing a payment with a price reads that, never {@code amount_paid} (cashu-mint#499).
 *
 * <p>When no durable repository is wired (legacy unit-test contexts) the task answers from the
 * payment gateway alone, as it always has, except that the regular route still refuses an id the
 * in-memory {@link VoucherQuoteRegistry} knows to be a voucher quote.
 */
public class MintQuoteStatusTask extends InstrumentedTask<PostMintQuoteResponse> {

    /** Which route is asking, and therefore which kind of quote it may describe. */
    public enum Kind {
        /** NUT-04 {@code /v1/mint/quote/{method}/{id}}. */
        REGULAR,
        /** Voucher extension {@code /v1/mint/quote/voucher/{method}/{id}}. */
        VOUCHER
    }

    /** Default unit emitted when no durable row pins one (v1 clients reject blank). */
    private static final String DEFAULT_UNIT = "sat";

    private final String quoteId;
    private final PaymentMethod method;
    private final String unit;
    private final Kind kind;
    private final MintProtocolService mintProtocolService;

    public MintQuoteStatusTask(@NonNull String quoteId, @NonNull PaymentMethod method, @NonNull Kind kind) {
        this(quoteId, method, null, kind, MintProtocolServiceFactory.getInstance());
    }

    public MintQuoteStatusTask(@NonNull String quoteId,
                               @NonNull PaymentMethod method,
                               String unit,
                               @NonNull Kind kind,
                               @NonNull MintProtocolService mintProtocolService) {
        this.quoteId = quoteId;
        this.method = method;
        this.unit = unit;
        this.kind = kind;
        this.mintProtocolService = mintProtocolService;
    }

    /** A regular-route status check. Kept for callers that predate {@link Kind}. */
    public MintQuoteStatusTask(@NonNull String quoteId,
                               @NonNull PaymentMethod method,
                               @NonNull MintProtocolService mintProtocolService) {
        this(quoteId, method, null, Kind.REGULAR, mintProtocolService);
    }

    @Override
    protected PostMintQuoteResponse doExecute() throws CashuErrorException {
        // Classify before touching the gateway, so a refused id costs no gateway call and a
        // wrong-route probe learns nothing about the quote's payment state.
        ResolvedQuote resolved = resolveQuote();

        // Treat blank/whitespace unit the same as absent so we never select a
        // gateway with an invalid unit string (consistent with creation).
        String requestedUnit = (unit == null || unit.isBlank()) ? null : unit;
        Gateway gateway = requestedUnit == null ? mintProtocolService.createGateway(method)
                : mintProtocolService.createGateway(method, requestedUnit);
        boolean paid = gateway.checkPaymentStatus(quoteId);
        String resolvedUnit = (requestedUnit != null) ? requestedUnit
                : (resolved.unit() != null && !resolved.unit().isBlank() ? resolved.unit() : DEFAULT_UNIT);
        String state = resolved.state(paid);

        PostMintQuoteResponse response = PostMintQuoteResponse.builder()
                .quoteId(quoteId)
                .request(gateway.getRequest(quoteId))
                .amount(clampToInt(resolved.amount()))
                .unit(resolvedUnit)
                .state(state)
                .paid(paid)
                .amountPaid(resolved.amountPaid(state))
                .amountIssued(resolved.amountIssued(state))
                .updatedAt(resolved.updatedAtEpochSecond())
                // Counted from the gateway's own creation time, the same base MintTask enforces
                // expiry against. Not the row's: a gateway without one (cash) returns the
                // seconds remaining, which only "now" turns into the right instant.
                .expiry(QuoteExpiry.absolute(gateway.getPaymentExpiry(quoteId),
                        QuoteExpiry.createdAt(gateway, quoteId)))
                .build();
        return kind == Kind.VOUCHER ? new VoucherMintQuoteResponse(response, resolved.charged()) : response;
    }

    private ResolvedQuote resolveQuote() throws CashuErrorException {
        MintQuoteRepository regularRepo = MintIntegrityContext.quoteRepository();
        VoucherQuoteRepository voucherRepo = MintIntegrityContext.voucherQuoteRepository();
        MintQuote regular = regularRepo == null ? null : regularRepo.findById(quoteId).orElse(null);
        VoucherQuote voucher = voucherRepo == null ? null : voucherRepo.findById(quoteId).orElse(null);

        return switch (kind) {
            case REGULAR -> {
                if (regular == null && (voucher != null || VoucherQuoteRegistry.isVoucherQuote(quoteId))) {
                    throw new CashuErrorException(CashuErrorCode.quote_not_found);
                }
                yield regular != null ? ResolvedQuote.of(regular, issuanceRecord()) : unclassified(regularRepo);
            }
            case VOUCHER -> {
                if (regular != null && voucher == null) {
                    throw new CashuErrorException(CashuErrorCode.voucher_quote_not_found);
                }
                yield voucher != null ? ResolvedQuote.of(voucher) : unclassified(voucherRepo);
            }
        };
    }

    /**
     * No row in the repository this route owns. With that repository wired, the id is unknown and
     * the answer is not-found; without it (legacy contexts) the payment gateway is all there is.
     */
    private ResolvedQuote unclassified(Object ownRepository) throws CashuErrorException {
        if (ownRepository != null) {
            throw new CashuErrorException(kind == Kind.VOUCHER
                    ? CashuErrorCode.voucher_quote_not_found
                    : CashuErrorCode.quote_not_found);
        }
        return ResolvedQuote.gatewayOnly();
    }

    /** The issuance ledger row for this quote, when the ledger is wired and holds one. */
    private Optional<IssuanceRecord> issuanceRecord() {
        IssuanceRecordRepository issuance = MintIntegrityContext.issuanceRecordRepository();
        return issuance == null ? Optional.empty() : issuance.findById(quoteId);
    }

    /**
     * The durable view of a quote, as the NUT-04 fields need it. Amounts stay {@code long}
     * (spec 001 FR-009) and are clamped to the DTO's {@code int} only at the wire boundary.
     *
     * @param amount     what may be minted: the quote amount, or a voucher's face value
     * @param charged    what the invoice charges: equal to {@code amount} for a regular quote,
     *                   the fee for a voucher quote. Reported as {@code charged_amount} on the
     *                   voucher route only; never as a NUT-04 accounting field
     * @param lifecycle  the durable lifecycle mapped to a wire state, or null when unknown
     */
    private record ResolvedQuote(long amount, long charged, String unit, String lifecycle,
                                 Instant createdAt, Instant updatedAt) {

        static ResolvedQuote of(MintQuote quote, Optional<IssuanceRecord> issuance) {
            String state = regularState(quote.lifecycleState(), issuance.isPresent());
            return new ResolvedQuote(quote.amount(), quote.amount(), quote.unit(), state,
                    quote.createdAt(), lastChange(quote, issuance));
        }

        /**
         * When the quote's accounting last changed. NUT-04: "Mints MUST update updated_at whenever
         * amount_paid or amount_issued changes", and never let it go backwards.
         *
         * <p>A quote left in {@code ISSUING} with its ledger row written is reported
         * {@code ISSUED}, so its {@code amount_issued} changed when the ledger row was written,
         * after the row's own {@code updated_at} was last stamped on entering {@code ISSUING}.
         * The ledger's {@code issued_at} is that moment; the later of the two is reported so the
         * value never falls behind one a client has already seen (cashu-mint#501).
         */
        private static Instant lastChange(MintQuote quote, Optional<IssuanceRecord> issuance) {
            Instant rowUpdated = quote.updatedAt();
            Instant issuedAt = issuance.map(IssuanceRecord::issuedAt).orElse(null);
            if (issuedAt == null) {
                return rowUpdated;
            }
            return rowUpdated == null || issuedAt.isAfter(rowUpdated) ? issuedAt : rowUpdated;
        }

        static ResolvedQuote of(VoucherQuote quote) {
            return new ResolvedQuote(quote.faceValue(), quote.chargedAmount(), quote.unit(),
                    voucherState(quote.lifecycleState()), quote.createdAt(), quote.updatedAt());
        }

        static ResolvedQuote gatewayOnly() {
            return new ResolvedQuote(0L, 0L, null, null, null, null);
        }

        /** The wire state, falling back to the gateway's payment flag where the row is silent. */
        String state(boolean paid) {
            if (lifecycle != null) {
                return lifecycle;
            }
            return paid ? LifecycleState.PAID.name() : LifecycleState.UNPAID.name();
        }

        /**
         * NUT-04 {@code amount_paid}: the value the payment entitles the payer to mint, in the
         * quote's unit. For a voucher that is the face value, not the fee its invoice charged:
         * NUT-04 requires {@code amount_issued <= amount_paid} and mints
         * {@code amount_paid - amount_issued}, and reporting the fee broke both (cashu-mint#499).
         */
        long amountPaid(String state) {
            return isPaidOrIssued(state) ? Math.max(0L, amount) : 0L;
        }

        /** NUT-04 {@code amount_issued}: what has been minted against the quote. */
        long amountIssued(String state) {
            return LifecycleState.ISSUED.name().equals(state) ? Math.max(0L, amount) : 0L;
        }

        long updatedAtEpochSecond() {
            Instant at = updatedAt != null ? updatedAt : createdAt;
            return at == null ? 0L : at.getEpochSecond();
        }

        private static boolean isPaidOrIssued(String state) {
            return LifecycleState.PAID.name().equals(state) || LifecycleState.ISSUED.name().equals(state);
        }
    }

    /**
     * Maps the durable regular lifecycle to the NUT-04 wire states. Returns null for states that
     * say nothing about payment, so the caller falls back to the gateway flag.
     *
     * <p>{@code ISSUING} is reported {@code ISSUED} when the issuance ledger row exists: the
     * signatures were produced and recorded, and only the final lifecycle CAS is outstanding.
     * Otherwise it is still {@code PAID}.
     */
    private static String regularState(LifecycleState lifecycle, boolean issuanceRecorded) {
        if (lifecycle == null) {
            return null;
        }
        return switch (lifecycle) {
            case ISSUED -> LifecycleState.ISSUED.name();
            case ISSUING -> issuanceRecorded ? LifecycleState.ISSUED.name() : LifecycleState.PAID.name();
            case PAID -> LifecycleState.PAID.name();
            default -> null;
        };
    }

    /** Maps the voucher lifecycle onto the same three NUT-04 wire states. */
    private static String voucherState(VoucherLifecycleState lifecycle) {
        if (lifecycle == null) {
            return null;
        }
        return switch (lifecycle) {
            case ISSUED -> LifecycleState.ISSUED.name();
            case FUNDED, ISSUING -> LifecycleState.PAID.name();
            default -> null;
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
