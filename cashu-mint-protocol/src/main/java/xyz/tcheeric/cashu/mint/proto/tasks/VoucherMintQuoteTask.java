package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuote;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuoteRepository;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.util.QuoteExpiry;
import xyz.tcheeric.cashu.mint.proto.util.VoucherFeeCalculator;
import xyz.tcheeric.cashu.mint.proto.util.VoucherFeeConfig;
import xyz.tcheeric.cashu.mint.proto.util.VoucherQuoteRegistry;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;

/**
 * Task for creating a voucher mint quote with percentage-based fee.
 *
 * <p><strong>Non-standard extension:</strong> vouchers are a vendor extension
 * on top of <a href="https://github.com/cashubtc/nuts/blob/main/04.md">NUT-04</a>;
 * see Constitution II and spec 003 FR-010/FR-013. The voucher path MUST NOT
 * appear under the NUT-06 {@code nuts} key.
 *
 * <p>Unlike regular mint quotes that charge the full face value, voucher mint
 * quotes charge only a percentage of the voucher face value as a minting fee.
 *
 * <h3>Spec 003 changes</h3>
 * When the durable repositories are wired ({@code cashu.mint.jpa.enabled=true}),
 * this task persists a {@code voucher_quote} row with
 * {@code lifecycle_state=UNFUNDED} so the voucher classification + face value
 * survive a restart (FR-001) and so the mint can later enforce the
 * funding-required gate (FR-002). The in-memory {@link VoucherQuoteRegistry}
 * is kept as a read-through accelerator (FR-003).
 *
 * @see VoucherFeeConfig
 * @see VoucherFeeCalculator
 * @see VoucherQuoteRegistry
 */
@Slf4j
public class VoucherMintQuoteTask extends InstrumentedTask<PostMintQuoteResponse> {

    private static final String VOUCHER_TYPE_CUSTOMER_PAID = "customer_paid";

    private final int faceValue;
    private final PaymentMethod method;
    private final String unit;
    private final MintProtocolService mintProtocolService;

    public VoucherMintQuoteTask(int faceValue, @NonNull PaymentMethod method) {
        this(faceValue, method, null, MintProtocolServiceFactory.getInstance());
    }

    public VoucherMintQuoteTask(int faceValue,
                                @NonNull PaymentMethod method,
                                String unit,
                                @NonNull MintProtocolService mintProtocolService) {
        this.faceValue = faceValue;
        this.method = method;
        this.unit = unit;
        this.mintProtocolService = mintProtocolService;
    }

    // Backward-compatible constructor used by tests: no unit parameter
    public VoucherMintQuoteTask(int faceValue,
                                @NonNull PaymentMethod method,
                                @NonNull MintProtocolService mintProtocolService) {
        this(faceValue, method, null, mintProtocolService);
    }

    @Override
    protected PostMintQuoteResponse doExecute() throws CashuErrorException {
        log.info("Creating voucher mint quote: faceValue={}, method={}", faceValue, method);

        // Load fee percentage and floor from configuration
        double feePercentage = VoucherFeeConfig.getFeePercentage();
        long minimumFee = VoucherFeeConfig.getMinimumFee();

        // Calculate the fee-based price, floored so it is actually chargeable.
        // Without the floor, every face value under `100 / feePercent` rounds
        // to zero and the gateway is asked for a zero-amount invoice, which
        // the whole chain accepts and the mint then refuses on the way back.
        long voucherPrice = VoucherFeeCalculator.calculateChargeableFee(faceValue, feePercentage, minimumFee);

        log.info("Voucher mint quote: faceValue={}, feePercent={}%, minFee={}, chargedPrice={}, method={}",
            faceValue, feePercentage, minimumFee, voucherPrice, method);

        // Fail here rather than three services downstream. A zero price can
        // only arise now from a deliberate 0% fee or a zero face value, and
        // neither can be invoiced: a zero-amount invoice settles trivially and
        // is then unbindable to the quote it paid for.
        if (voucherPrice <= 0) {
            log.warn("voucher_quote_refused reason=non_positive_price faceValue={} feePercent={}% minFee={}",
                faceValue, feePercentage, minimumFee);
            throw new CashuErrorException(
                    "{\"error\":\"voucher_price_not_chargeable\"}");
        }

        // Create gateway and call createMintQuote with fee price (not face value)
        Gateway gateway = unit == null ? mintProtocolService.createGateway(method)
                : mintProtocolService.createGateway(method, unit);

        // Record the quote BEFORE the invoice exists (cashu-mint#469).
        //
        // Raising the invoice is irreversible: it is payable the moment the
        // gateway returns, and no gateway here can withdraw it. This task
        // used to raise it first and record the quote second, so any failure
        // of the write left a payable invoice with no record behind it. On
        // staging the customer paid, the mint ACCEPTED the payment, and the
        // request that created the quote had already returned 90008 to a
        // client that never came back. Twelve quotes sat PAID with nothing
        // issued and no process responsible for them.
        //
        // Fixing the one trigger (a zero price rejected by the CHECK
        // constraint) did not fix the ordering: a transient database error, or
        // any future constraint, stranded a payable invoice the same way.
        //
        // So the mint chooses the id, writes the row, and only then raises the
        // invoice under that same id. Each failure now lands on the safe side:
        //   - write fails     -> refused, and nothing is yet payable
        //   - invoice fails   -> an UNFUNDED row with no invoice, which nothing
        //                        can pay against or mint from: inert
        // The old order failed the other way round, and that way took money.
        //
        // The orphan in the second case is also unreachable: the exception
        // propagates before the response is built, so its id is a random UUID
        // no client ever sees, and nothing can poll or mint against it. It is
        // not reclaimed: VoucherIdentityRetentionPurgeService only nulls
        // identity columns on terminal rows (ISSUED/EXPIRED/FAILED) and never
        // deletes, so an orphan stays. That costs ~621 bytes per failed
        // invoice and needs no owner, where the failure it replaces cost the
        // customer's payment. Staging held zero UNFUNDED rows when this landed.
        String quoteId = UUID.randomUUID().toString();
        persistVoucherQuote(quoteId, voucherPrice);

        // IRREVERSIBLE FROM HERE. Every precondition that can refuse this
        // quote, including the write above, must stay above this line.
        String invoicedId = gateway.createMintQuote(quoteId, (int) voucherPrice, null);

        // The row above names quoteId. If the gateway raised the invoice under
        // any other id, the payment would arrive for a quote the mint has no
        // record of, which is #469 again one step later. The contract says it
        // cannot happen; this makes a gateway that breaks it loud rather than
        // silently stranding a payment. It fires after the invoice exists, so
        // it reports the breach rather than preventing it: the prevention is
        // the gateway's own contract, pinned by PhoenixdGatewayTest.
        if (!quoteId.equals(invoicedId)) {
            log.error("voucher_quote gateway_changed_quote_id recorded={} invoiced={} gateway={}",
                    quoteId, invoicedId, gateway.getClass().getSimpleName());
            throw new CashuErrorException("{\"error\":\"voucher_quote_id_mismatch\"}");
        }

        VoucherQuoteRegistry.storeFaceValue(quoteId, faceValue);

        // Retrieve request and expiry from gateway
        String request = gateway.getRequest(quoteId);
        Integer expiry = gateway.getPaymentExpiry(quoteId);

        log.debug("Voucher mint quote created: quoteId={}, request={}, expiry={}",
            quoteId, request, expiry);

        // NUT-04 v1 — modern wallets require amount/unit/state on the response.
        // The mintable amount for a voucher quote is the voucher FACE VALUE (what
        // the blinded outputs sum to and what MintTask validates against), NOT the
        // charged fee — the invoice/request still charges only `voucherPrice`.
        // Emitting the fee here would make wallets size outputs to the fee and the
        // subsequent mint would fail with mint_amount_mismatch. A fresh quote is
        // always UNPAID. The gateway's relative TTL is converted to the absolute Unix
        // timestamp NUT-04 requires (#494). faceValue is already an int, so no
        // narrowing cast is needed.
        return PostMintQuoteResponse.builder()
                .quoteId(quoteId)
                .request(request)
                .amount(faceValue)
                .unit(unit != null && !unit.isBlank() ? unit : "sat")
                .state("UNPAID")
                .updatedAt(Instant.now().getEpochSecond())
                .expiry(QuoteExpiry.ofNewQuote(expiry))
                .build();
    }

    private void persistVoucherQuote(String quoteId, long chargedAmount) throws CashuErrorException {
        VoucherQuoteRepository repo = MintIntegrityContext.voucherQuoteRepository();
        if (repo == null) {
            // Legacy unit-test context — no JPA repo wired, registry-only.
            return;
        }
        try {
            // Spec 003 review fix — fee semantics for fee-only voucher
            // pricing: the gateway invoice is created for the fee
            // (chargedAmount = voucherPrice = faceValue * feePercent),
            // which is strictly less than faceValue. So fee == chargedAmount
            // for this variant — the customer pays the fee, the merchant
            // covers the face value via the funding row. Recording
            // max(0, chargedAmount - faceValue) was always 0 and lost the
            // audit signal.
            long fee = chargedAmount;
            VoucherQuoteRecord record = new VoucherQuoteRecord(
                    quoteId,
                    VOUCHER_TYPE_CUSTOMER_PAID,
                    faceValue,
                    chargedAmount,
                    fee,
                    unit != null ? unit : "sat",
                    requestHash(quoteId, faceValue, chargedAmount));
            repo.save(record);
            log.info("voucher_quote persisted quote_id={} face_value={} charged={} fee={} lifecycle=UNFUNDED",
                    quoteId, faceValue, chargedAmount, fee);
        } catch (RuntimeException e) {
            // Fail closed. This runs before the invoice is raised (#469), so a
            // failure here refuses the quote while nothing is yet payable: the
            // client retries a quote it was never charged for. Before the
            // reorder the same failure arrived after a payable invoice already
            // existed, which is how twelve paid quotes were stranded.
            log.error("voucher_quote persist_failed quote_id={}", quoteId, e);
            throw new CashuErrorException(
                    "{\"error\":\"voucher_quote_persist_failed\"}");
        }
    }

    private static String requestHash(String quoteId, int faceValue, long chargedAmount) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Spec 003 review fix — pin charset so request_hash is stable
            // across environments (default JVM charset is platform-dependent).
            byte[] bytes = digest.digest(
                    (quoteId + "|" + faceValue + "|" + chargedAmount)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Minimal anonymous implementation of {@link VoucherQuote} used to hand
     * a fresh record to the JPA adapter. The adapter copies the fields onto
     * a {@code VoucherQuoteEntity}; this saves us from depending on the JPA
     * module from the protocol task.
     */
    private record VoucherQuoteRecord(
            String quoteId,
            String voucherType,
            long faceValue,
            long chargedAmount,
            long fee,
            String unit,
            String requestHash
    ) implements VoucherQuote {
        @Override public String merchantId() { return null; }
        @Override public String customerId() { return null; }
        @Override public String fundingId() { return null; }
        @Override public VoucherLifecycleState lifecycleState() { return VoucherLifecycleState.UNFUNDED; }
        @Override public String idempotencyKey() { return null; }
        @Override public Instant createdAt() { return null; }
        @Override public Instant updatedAt() { return null; }
    }
}
