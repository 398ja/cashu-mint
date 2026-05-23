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
import xyz.tcheeric.cashu.mint.proto.util.VoucherFeeCalculator;
import xyz.tcheeric.cashu.mint.proto.util.VoucherFeeConfig;
import xyz.tcheeric.cashu.mint.proto.util.VoucherQuoteRegistry;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

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

        // Load fee percentage from configuration
        double feePercentage = VoucherFeeConfig.getFeePercentage();

        // Calculate fee-based price
        long voucherPrice = VoucherFeeCalculator.calculateFee(faceValue, feePercentage);

        log.info("Voucher mint quote: faceValue={}, feePercent={}%, chargedPrice={}, method={}",
            faceValue, feePercentage, voucherPrice, method);

        // Create gateway and call createMintQuote with fee price (not face value)
        Gateway gateway = unit == null ? mintProtocolService.createGateway(method)
                : mintProtocolService.createGateway(method, unit);

        String quoteId = gateway.createMintQuote((int) voucherPrice, null);

        // Spec 003 FR-001/FR-003 — persist the durable voucher quote so the
        // classification and face value survive a restart. The in-memory
        // registry becomes a read-through cache (still populated for the
        // legacy fast path).
        persistVoucherQuote(quoteId, voucherPrice);

        VoucherQuoteRegistry.storeFaceValue(quoteId, faceValue);

        // Retrieve request and expiry from gateway
        String request = gateway.getRequest(quoteId);
        Integer expiry = gateway.getPaymentExpiry(quoteId);

        log.debug("Voucher mint quote created: quoteId={}, request={}, expiry={}",
            quoteId, request, expiry);

        return PostMintQuoteResponse.builder()
                .quoteId(quoteId)
                .request(request)
                .expiry(expiry)
                .build();
    }

    private void persistVoucherQuote(String quoteId, long chargedAmount) {
        VoucherQuoteRepository repo = MintIntegrityContext.voucherQuoteRepository();
        if (repo == null) {
            return;
        }
        try {
            VoucherQuoteRecord record = new VoucherQuoteRecord(
                    quoteId,
                    VOUCHER_TYPE_CUSTOMER_PAID,
                    faceValue,
                    chargedAmount,
                    Math.max(0L, chargedAmount - faceValue),
                    unit != null ? unit : "sat",
                    requestHash(quoteId, faceValue, chargedAmount));
            repo.save(record);
            log.info("voucher_quote persisted quote_id={} face_value={} charged={} lifecycle=UNFUNDED",
                    quoteId, faceValue, chargedAmount);
        } catch (RuntimeException e) {
            // Non-fatal for the legacy path: the registry still has the face value.
            // Spec 003 production deploys MUST run with the repo wired, but in
            // tests / legacy contexts a save failure should not bring down quote
            // creation. The mint branch will detect the missing durable row and
            // surface a typed error if it tries to issue.
            log.warn("voucher_quote persist_failed quote_id={} reason={}", quoteId, e.getMessage());
        }
    }

    private static String requestHash(String quoteId, int faceValue, long chargedAmount) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(
                    (quoteId + "|" + faceValue + "|" + chargedAmount).getBytes());
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
