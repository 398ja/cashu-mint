package xyz.tcheeric.cashu.mint.proto.metrics;

/**
 * Bounded label domain for {@code cashu_mint_voucher_rejected_total} — issue
 * #341.
 *
 * <p>Voucher rejections used to be one counter per reason
 * ({@code ..._funding_required_total}, {@code ..._face_value_not_backed_total},
 * {@code ..._iou_denied_total}). They are one metric with a reason dimension:
 * every one of them means "a voucher mint was refused, because X". Splitting
 * them meant a new reason cost a new metric, a new panel and a new alert rule,
 * which is how the catalogue drifted in the first place.
 *
 * <p>The label domain is this enum rather than a free string so cardinality
 * stays bounded by construction — a call site cannot invent a reason.
 */
public enum VoucherRejectionReason {

    /** No durable funding row resolved for the quote (spec 003 FR-005). */
    FUNDING_REQUIRED,

    /** Funding did not cover the face value in the quote's unit (spec 006). */
    FACE_VALUE_NOT_BACKED,

    /** Funding was an IOU and {@code voucher.iou-policy} is DENY (spec 003 FR-006). */
    IOU_NOT_PERMITTED;

    /**
     * Label value for this reason: the lower-case enum name, which matches the
     * {@code ErrorResponse} code the caller receives, so an operator can pivot
     * from a client error straight to the series.
     *
     * @return the Prometheus label value
     */
    public String label() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
