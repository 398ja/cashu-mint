package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * NUT-04 mint-quote creation: asks the gateway for a payment request and
 * returns the quote id / payment string / expiry.
 *
 * <p>Spec references (FR-014 — pinned-commit URLs tracked as a follow-up;
 * current links use {@code main}):
 * <ul>
 *   <li>NUT-04: <a href="https://github.com/cashubtc/nuts/blob/main/04.md">cashubtc/nuts §04</a> — mint tokens (quote endpoint)</li>
 *   <li>NUT-06: <a href="https://github.com/cashubtc/nuts/blob/main/06.md">cashubtc/nuts §06</a> — mint info (advertised limits)</li>
 *   <li>NUT-20: <a href="https://github.com/cashubtc/nuts/blob/main/20.md">cashubtc/nuts §20</a> — signed mint quote (where supported)</li>
 * </ul>
 *
 * <p>Spec 001 T112 / FR-004: when a {@link MintQuoteRepository} is provided,
 * the task persists the new quote row in state {@code UNPAID} with a
 * deterministic {@code request_hash} (SHA-256 over {@code amount|unit|method})
 * that lets follow-on writes detect a tampered quote request. The persistence
 * is gated on the repository being non-null so legacy unit-test contexts that
 * never wire JPA stay unaffected.
 */
@Slf4j
public class MintQuoteTask extends InstrumentedTask<PostMintQuoteResponse> {

    private final long amount;
    private final PaymentMethod method;
    private final String unit;
    private final MintProtocolService mintProtocolService;
    private final MintQuoteRepository mintQuoteRepository;
    private final String mintUrl;

    public MintQuoteTask(long amount, @NonNull PaymentMethod method) {
        this(amount, method, null, MintProtocolServiceFactory.getInstance(), null, null);
    }

    // Backward-compatible int constructor; widens to long internally.
    public MintQuoteTask(int amount, @NonNull PaymentMethod method) {
        this((long) amount, method);
    }

    public MintQuoteTask(long amount,
                         @NonNull PaymentMethod method,
                         String unit,
                         @NonNull MintProtocolService mintProtocolService) {
        this(amount, method, unit, mintProtocolService, null, null);
    }

    // Backward-compatible constructor used by tests: no unit parameter
    public MintQuoteTask(long amount,
                         @NonNull PaymentMethod method,
                         @NonNull MintProtocolService mintProtocolService) {
        this(amount, method, null, mintProtocolService, null, null);
    }

    /**
     * Spec 001 constructor — persists the quote via the durable repository
     * when {@code mintQuoteRepository} is non-null. {@code mintUrl} is stored
     * on the row so downstream auditors can attribute the issuance to the
     * specific mint instance.
     */
    public MintQuoteTask(long amount,
                         @NonNull PaymentMethod method,
                         String unit,
                         @NonNull MintProtocolService mintProtocolService,
                         MintQuoteRepository mintQuoteRepository,
                         String mintUrl) {
        this.amount = amount;
        this.method = method;
        this.unit = unit;
        this.mintProtocolService = mintProtocolService;
        this.mintQuoteRepository = mintQuoteRepository;
        this.mintUrl = mintUrl;
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

        if (mintQuoteRepository != null) {
            String resolvedUnit = unit != null ? unit : "sat";
            String resolvedMintUrl = mintUrl != null ? mintUrl : "";
            try {
                mintQuoteRepository.save(new NewQuote(
                        quoteId,
                        amount,
                        resolvedUnit,
                        resolvedMintUrl,
                        method.name(),
                        quoteId,
                        LifecycleState.UNPAID,
                        requestHash(amount, resolvedUnit, method),
                        Instant.now()));
            } catch (RuntimeException e) {
                log.error("mint_quote_persist_failed quote_id={} amount={} unit={}",
                        quoteId, amount, resolvedUnit, e);
                throw new CashuErrorException("mint_quote_persist_failed");
            }
        }

        return PostMintQuoteResponse.builder()
                .quoteId(quoteId)
                .request(request)
                .expiry(expiry)
                .build();
    }

    private static String requestHash(long amount, String unit, PaymentMethod method) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Long.toString(amount).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(unit.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(method.name().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Inline {@link MintQuote} carrier for the quote-creation insert. The JPA
     * adapter unwraps this to a {@code MintQuoteEntity} via field copy; the
     * persisted row picks up DB-side timestamps and the JPA {@code @Version}.
     */
    private record NewQuote(
            String quoteId,
            long amount,
            String unit,
            String mintUrl,
            String paymentMethod,
            String invoiceId,
            LifecycleState lifecycleState,
            String requestHash,
            Instant createdAt) implements MintQuote {

        @Override
        public Instant updatedAt() {
            return createdAt;
        }
    }
}
