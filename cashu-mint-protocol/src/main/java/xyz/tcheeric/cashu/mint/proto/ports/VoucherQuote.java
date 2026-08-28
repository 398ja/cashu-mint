package xyz.tcheeric.cashu.mint.proto.ports;

import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;

import java.time.Instant;

/**
 * Spec 003 — durable view of a voucher mint quote. Sibling to
 * {@link MintQuote} (research R1); the two namespaces stay disjoint.
 *
 * <p>All financial-amount fields are {@code long} per Constitution I /
 * FR-011. {@code fundingId} is null while the quote is awaiting funding;
 * once a {@code VoucherFunding} row attaches, the lifecycle CAS-transitions
 * {@code UNFUNDED → FUNDED}.

 */
public interface VoucherQuote {

    String quoteId();

    /** Informational label (e.g. {@code customer_paid}, {@code merchant_funded}, {@code iou}). */
    String voucherType();

    /** Spendable face value the voucher represents. */
    long faceValue();

    /** Amount the customer / merchant was charged (face_value + fee, or fee only when face_value is the payout). */
    long chargedAmount();

    /** {@code chargedAmount - faceValue} or 0; persisted for audit. */
    long fee();

    /**
     * Spec 035 — sum of blinded message amounts captured at voucher
     * issuance time (the original sat-denominated proof sum, before any
     * partial spends consume proofs). Used by
     * {@code /v1/vouchers/{voucherId}/provenance} to compute
     * {@code issuance_ratio = face_value / original_token_amount} so the
     * wallet can correct partial-spend display on the receive side.
     *
     * <p>Nullable. Returns {@code null} for legacy rows issued before
     * the V20260601_007 migration; the provenance endpoint surfaces a
     * {@code null} {@code issuance_ratio} in that case and the wallet
     * falls back to the embedded face_value path (spec-035 iter-6
     * frontend already handles this).
     *
     * <p>Default {@code null} so non-JPA test fixtures (in-memory or
     * builder-style) compile without forced override.
     */
    default Long originalTokenAmount() {
        return null;
    }

    String unit();

    /** npub / principal of the merchant when merchant-funded or IOU. */
    String merchantId();

    /** npub / principal of the customer when customer-paid. */
    String customerId();

    /** FK into {@code voucher_funding}; null while UNFUNDED. */
    String fundingId();

    VoucherLifecycleState lifecycleState();

    /** Client-supplied; partial-unique across non-null values. */
    String idempotencyKey();

    /** SHA-256 of the canonical request body — paired with idempotencyKey for tamper detection. */
    String requestHash();

    Instant createdAt();

    Instant updatedAt();
}
