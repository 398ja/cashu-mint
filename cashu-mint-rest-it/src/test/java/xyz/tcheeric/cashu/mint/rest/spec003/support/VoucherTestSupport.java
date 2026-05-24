package xyz.tcheeric.cashu.mint.rest.spec003.support;

import xyz.tcheeric.cashu.mint.jpa.entity.CustomerPaymentFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.MerchantDebitFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.MerchantIouFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherIssuanceEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuoteEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.WebhookEventEntity;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherFundingSource;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.WebhookEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Spec 003 T004 — builders for the spec-003 entities. Keeps the ITs
 * focused on assertions rather than entity boilerplate.
 *
 * <p>Each builder returns an unsaved entity with sane defaults; tests
 * override individual fields before persisting via the JPA repository
 * autowired by the IT base class.
 */
public final class VoucherTestSupport {

    public static final String DEFAULT_UNIT = "sat";
    public static final String VOUCHER_TYPE_CUSTOMER_PAID = "customer_paid";
    public static final String VOUCHER_TYPE_MERCHANT_FUNDED = "merchant_funded";
    public static final String VOUCHER_TYPE_IOU = "iou";

    private VoucherTestSupport() {
    }

    // ---------------------------------------------------------------
    // VoucherQuote
    // ---------------------------------------------------------------

    public static VoucherQuoteEntity unfundedQuote(String quoteId, long faceValue) {
        VoucherQuoteEntity quote = new VoucherQuoteEntity();
        quote.setQuoteId(quoteId);
        quote.setVoucherType(VOUCHER_TYPE_CUSTOMER_PAID);
        quote.setFaceValue(faceValue);
        quote.setChargedAmount(faceValue);
        quote.setFee(0L);
        quote.setUnit(DEFAULT_UNIT);
        quote.setLifecycleState(VoucherLifecycleState.UNFUNDED);
        quote.setRequestHash(hashOf(quoteId, faceValue));
        return quote;
    }

    public static VoucherQuoteEntity fundedQuote(String quoteId, long faceValue, String fundingId) {
        VoucherQuoteEntity quote = unfundedQuote(quoteId, faceValue);
        quote.setFundingId(fundingId);
        quote.setLifecycleState(VoucherLifecycleState.FUNDED);
        return quote;
    }

    // ---------------------------------------------------------------
    // VoucherFunding variants
    // ---------------------------------------------------------------

    public static CustomerPaymentFundingEntity customerPaymentFunding(String fundingId, long amount,
                                                                     String provider, String providerEventId) {
        CustomerPaymentFundingEntity funding = new CustomerPaymentFundingEntity();
        funding.setFundingId(fundingId);
        funding.setAmount(amount);
        funding.setUnit(DEFAULT_UNIT);
        funding.setFundingSource(VoucherFundingSource.CUSTOMER_PAYMENT);
        funding.setProvider(provider);
        funding.setProviderEventId(providerEventId);
        return funding;
    }

    public static MerchantDebitFundingEntity merchantDebitFunding(String fundingId, long amount,
                                                                 String merchantId, String merchantDebitId) {
        MerchantDebitFundingEntity funding = new MerchantDebitFundingEntity();
        funding.setFundingId(fundingId);
        funding.setAmount(amount);
        funding.setUnit(DEFAULT_UNIT);
        funding.setFundingSource(VoucherFundingSource.MERCHANT_DEBIT);
        funding.setMerchantId(merchantId);
        funding.setMerchantDebitId(merchantDebitId);
        return funding;
    }

    public static MerchantIouFundingEntity merchantIouFunding(String fundingId, long amount,
                                                              String merchantId, String iouId,
                                                              String policyProfile) {
        MerchantIouFundingEntity funding = new MerchantIouFundingEntity();
        funding.setFundingId(fundingId);
        funding.setAmount(amount);
        funding.setUnit(DEFAULT_UNIT);
        funding.setFundingSource(VoucherFundingSource.MERCHANT_IOU);
        funding.setMerchantId(merchantId);
        funding.setIouId(iouId);
        funding.setPolicyProfile(policyProfile);
        return funding;
    }

    // ---------------------------------------------------------------
    // VoucherIssuance
    // ---------------------------------------------------------------

    public static VoucherIssuanceEntity voucherIssuance(String voucherQuoteId, String fundingId,
                                                       String outputsHash) {
        VoucherIssuanceEntity issuance = new VoucherIssuanceEntity();
        issuance.setVoucherQuoteId(voucherQuoteId);
        issuance.setFundingId(fundingId);
        // Spec 004 V20260601_005 dropped the standalone issuance_id column;
        // VoucherIssuance.issuanceId() now returns the same value as
        // voucherQuoteId.
        issuance.setOutputsHash(outputsHash);
        issuance.setIssuedAt(Instant.now());
        return issuance;
    }

    // ---------------------------------------------------------------
    // WebhookEvent (for resolver fallback)
    // ---------------------------------------------------------------

    public static WebhookEventEntity acceptedWebhookEvent(String provider, String providerEventId,
                                                         String quoteId, long amount) {
        WebhookEventEntity e = new WebhookEventEntity();
        e.setProvider(provider);
        e.setProviderEventId(providerEventId);
        e.setQuoteId(quoteId);
        e.setAmount(amount);
        e.setUnit(DEFAULT_UNIT);
        e.setPaymentMethod("bolt11");
        e.setOutcome(WebhookEvent.Outcome.accepted);
        e.setReceivedAt(Instant.now());
        return e;
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    public static String newQuoteId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    public static String newFundingId() {
        return UUID.randomUUID().toString();
    }

    /** Stable test hash — 64 hex chars, unique per (quoteId, faceValue). */
    private static String hashOf(String quoteId, long faceValue) {
        String hex = Integer.toHexString((quoteId + ":" + faceValue).hashCode());
        // Left-pad to 64 hex chars; safe for any input length.
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < 64 - hex.length(); i++) {
            sb.append('0');
        }
        sb.append(hex);
        return sb.toString();
    }

    /** Generic VoucherFundingEntity getter useful for polymorphic checks. */
    public static VoucherFundingSource sourceOf(VoucherFundingEntity entity) {
        return entity.getFundingSource();
    }
}
