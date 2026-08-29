package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.util.AmountLimitContext;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Properties;

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
        // Treat blank/whitespace unit the same as absent so we never select a
        // gateway with — or persist — an invalid unit string (the JPA column is
        // NOT NULL and v1 clients expect a real unit, never blank).
        String requestedUnit = (unit == null || unit.isBlank()) ? null : unit;
        if (amount > Integer.MAX_VALUE || amount <= 0) {
            throw new CashuErrorException(CashuErrorCode.invalid_quote_amount);
        }
        String resolvedUnit = requestedUnit != null ? requestedUnit : resolveDefaultUnit();
        // Issue #390: reject before touching the gateway what /v1/info says this
        // mint will not issue, so the advertised max_amount is the enforced one.
        AmountLimitContext.policy().requireWithinMintLimits(amount, resolvedUnit);
        Gateway gateway = requestedUnit == null ? mintProtocolService.createGateway(method)
                : mintProtocolService.createGateway(method, requestedUnit);
        // Boundary cast: payment-adapter Gateway#createMintQuote still takes Integer.
        // Tracked cross-repo per spec 001 research R6 (Gateway interface migration).
        String quoteId = gateway.createMintQuote((int) amount, null);
        String request = gateway.getRequest(quoteId);
        Integer expiry = gateway.getPaymentExpiry(quoteId);

        if (mintQuoteRepository != null) {
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
                throw new CashuErrorException(CashuErrorCode.internal_error, "Mint quote could not be persisted");
            }
        }

        return PostMintQuoteResponse.builder()
                .quoteId(quoteId)
                .request(request)
                // NUT-04 v1 — modern wallets (cashu-ts >= 4.x) require amount/unit/state
                // on every mint-quote response; a fresh quote is always UNPAID. The
                // gateway's expiry (a relative TTL) is passed through unchanged — the
                // client normalizes relative-vs-absolute itself.
                .amount((int) amount)
                .unit(resolvedUnit)
                .state(LifecycleState.UNPAID.name())
                .expiry(expiry)
                .build();
    }

    /**
     * Resolve the unit to persist when the caller didn't pass one explicitly.
     * Reads {@code cashu.units} from {@code proto.properties} — the same
     * configuration {@link xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil}
     * uses to pick a gateway when only the method (no unit) is provided. This
     * keeps the durable {@code mint_quote.unit} aligned with whichever unit
     * the gateway will actually transact in.
     *
     * <p>Falls back to {@code "sat"} if the property is missing.
     */
    private static String resolveDefaultUnit() {
        try (InputStream input = new ClassPathResource("proto.properties").getInputStream()) {
            Properties props = new Properties();
            props.load(input);
            String unit = props.getProperty("cashu.units");
            if (unit != null && !unit.isBlank()) {
                return unit.trim();
            }
        } catch (IOException ignored) {
            // fall through to default
        }
        return "sat";
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
