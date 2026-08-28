package xyz.tcheeric.cashu.mint.proto.ports;

import xyz.tcheeric.cashu.mint.proto.domain.VoucherFundingSource;

import java.time.Instant;

/**
 * Spec 003 — durable view of the asset side backing a voucher quote. Three
 * concrete variants live in the JPA adapter as JOINED-inheritance
 * subclasses ({@code CustomerPaymentFundingEntity},
 * {@code MerchantDebitFundingEntity}, {@code MerchantIouFundingEntity});
 * the protocol module only needs the discriminator + amount/unit fields
 * to decide whether the funding row is acceptable for issuance.
 *
 * <p>Variant-specific fields (e.g. {@code providerEventId},
 * {@code merchantDebitId}, {@code iouId}) are exposed via the optional
 * accessors below; callers narrow on {@link #fundingSource()} before
 * reading them.

 */
public interface VoucherFunding {

    String fundingId();

    VoucherFundingSource fundingSource();

    long amount();

    String unit();

    Instant createdAt();

    /** {@code CUSTOMER_PAYMENT} only — provider event id (e.g. webhook event id). */
    default String providerEventId() { return null; }

    /** {@code CUSTOMER_PAYMENT} only — gateway identifier. */
    default String provider() { return null; }

    /** {@code MERCHANT_DEBIT} / {@code MERCHANT_IOU} — merchant npub / principal. */
    default String merchantId() { return null; }

    /** {@code MERCHANT_DEBIT} only — merchant-ledger debit id. */
    default String merchantDebitId() { return null; }

    /** {@code MERCHANT_IOU} only — operator-issued IOU id. */
    default String iouId() { return null; }

    /** {@code MERCHANT_IOU} only — Spring profile in effect at issuance (FR-006). */
    default String policyProfile() { return null; }
}
