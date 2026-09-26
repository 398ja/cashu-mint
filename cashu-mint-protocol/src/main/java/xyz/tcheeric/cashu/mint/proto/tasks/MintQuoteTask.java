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
import xyz.tcheeric.cashu.mint.proto.util.QuoteExpiry;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;

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

    /** NUT-20 — the key the quote is locked to, or null for an unlocked quote. */
    private final String pubkey;

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
        this(amount, method, unit, mintProtocolService, mintQuoteRepository, mintUrl, null);
    }

    /**
     * NUT-20 constructor — locks the quote to {@code pubkey} when one is supplied, so only the
     * holder of the matching private key can mint it.
     */
    public MintQuoteTask(long amount,
                         @NonNull PaymentMethod method,
                         String unit,
                         @NonNull MintProtocolService mintProtocolService,
                         MintQuoteRepository mintQuoteRepository,
                         String mintUrl,
                         String pubkey) {
        this.pubkey = pubkey;
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
        String quoteId = raiseInvoice(gateway, resolvedUnit);
        String request = gateway.getRequest(quoteId);
        Integer expiry = gateway.getPaymentExpiry(quoteId);

        return PostMintQuoteResponse.builder()
                .quoteId(quoteId)
                .pubkey(pubkey)
                .request(request)
                // NUT-04 v1 — modern wallets (cashu-ts >= 4.x) require amount/unit/state
                // on every mint-quote response; a fresh quote is always UNPAID. The
                // gateway's expiry is a relative TTL and NUT-04 requires an absolute Unix
                // timestamp; passing the TTL through made cashu-ts read "60" as 1970 and
                // refuse the quote as expired (#494).
                .amount((int) amount)
                .unit(resolvedUnit)
                .state(LifecycleState.UNPAID.name())
                .updatedAt(Instant.now().getEpochSecond())
                .expiry(QuoteExpiry.ofNewQuote(expiry))
                .build();
    }

    /**
     * Raises the payment request and returns the quote id it is filed under.
     *
     * <p>With the durable repository wired, the quote row is written <b>before</b> the invoice
     * exists (cashu-mint#502, the regular-quote half of #469). Raising an invoice is irreversible:
     * it is payable the moment the gateway returns, and no gateway here can withdraw it. This task
     * used to raise it first and write the row second, so a failed write left a payable invoice
     * whose payment the webhook could not match to any quote: the payer was charged and nothing
     * could ever be minted. Now each failure lands on the safe side:
     * <ul>
     *   <li>the write fails: refused, and nothing is payable yet;</li>
     *   <li>the invoice fails: a row no client ever learns the id of, which nothing can pay or
     *       mint against, marked {@code FAILED} so it does not read as an open quote.</li>
     * </ul>
     *
     * <p>The mint chooses the id so the row and the invoice can share it. A gateway that raises
     * the invoice under a different id is refused: the row would name an invoice that does not
     * exist, stranding the payment one step later. The check runs after the invoice exists, so it
     * reports the breach rather than preventing it; prevention is the gateway's contract.
     *
     * <p>A gateway that cannot take a caller-chosen id (payment-adapter's default refuses with
     * {@link UnsupportedOperationException}; only Phoenixd honours one today) is not a reason to
     * refuse every regular quote on it. For those the gateway chooses the id and the row is
     * written after the invoice, the order this task always used, and a WARN names the gateway so
     * the residual risk is visible. The probe raises nothing: the refusal comes before any invoice.
     * The pre-written row is then marked {@code FAILED}: its id is never invoiced or shown.
     *
     * <p>Without the repository (legacy unit-test contexts) nothing is recorded, and the gateway
     * keeps choosing its own id as before.
     */
    private String raiseInvoice(Gateway gateway, String resolvedUnit) throws CashuErrorException {
        if (mintQuoteRepository == null) {
            // Boundary cast: payment-adapter Gateway#createMintQuote takes Integer. The amount is
            // bounded to Integer.MAX_VALUE above.
            return gateway.createMintQuote((int) amount, null);
        }
        String quoteId = UUID.randomUUID().toString();
        recordQuote(quoteId, resolvedUnit);

        // IRREVERSIBLE FROM HERE. Every precondition that can refuse this quote, including the
        // write above, must stay above this line.
        String invoicedId;
        try {
            invoicedId = gateway.createMintQuote(quoteId, (int) amount, null);
        } catch (UnsupportedOperationException cannotTakeOurId) {
            abandon(quoteId);
            return raiseInvoiceUnderTheGatewaysId(gateway, resolvedUnit);
        } catch (RuntimeException invoiceFailed) {
            abandon(quoteId);
            throw invoiceFailed;
        }
        if (!quoteId.equals(invoicedId)) {
            log.error("mint_quote gateway_changed_quote_id recorded={} invoiced={} gateway={}",
                    quoteId, invoicedId, gateway.getClass().getSimpleName());
            abandon(quoteId);
            throw new CashuErrorException(CashuErrorCode.internal_error,
                    "Payment gateway raised the invoice under a different quote id");
        }
        return quoteId;
    }

    /**
     * Marks a pre-written row whose invoice was never raised under its id as {@code FAILED}, so it
     * does not sit as an open {@code UNPAID} quote. Best effort: the row is inert either way (no
     * client has its id and no invoice carries it), so a failure here must not mask the outcome
     * the caller is reporting.
     */
    private void abandon(String quoteId) {
        try {
            mintQuoteRepository.casLifecycle(quoteId, LifecycleState.UNPAID, LifecycleState.FAILED);
        } catch (RuntimeException e) {
            log.warn("mint_quote abandon_failed quote_id={} reason={}", quoteId, e.toString());
        }
    }

    /**
     * The pre-#502 order, for a gateway that chooses its own ids: raise the invoice, then record
     * the quote under the id the gateway returned. A failed write here strands a payable invoice,
     * which is why the WARN names the gateway and why the caller-chosen path is preferred.
     */
    private String raiseInvoiceUnderTheGatewaysId(Gateway gateway, String resolvedUnit)
            throws CashuErrorException {
        log.warn("mint_quote gateway_cannot_take_quote_id gateway={} order=invoice_then_record",
                gateway.getClass().getSimpleName());
        String quoteId = gateway.createMintQuote((int) amount, null);
        recordQuote(quoteId, resolvedUnit);
        return quoteId;
    }

    /** Writes the {@code UNPAID} quote row, refusing the quote when the write fails. */
    private void recordQuote(String quoteId, String resolvedUnit) throws CashuErrorException {
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
                    Instant.now(),
                    pubkey));
        } catch (RuntimeException e) {
            // Fail closed, and before the invoice exists: the client is refused a quote it was
            // never charged for.
            log.error("mint_quote_persist_failed quote_id={} amount={} unit={}",
                    quoteId, amount, resolvedUnit, e);
            throw new CashuErrorException(CashuErrorCode.internal_error, "Mint quote could not be persisted");
        }
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
            Instant createdAt,
            String pubkey) implements MintQuote {

        @Override
        public Instant updatedAt() {
            return createdAt;
        }
    }
}
