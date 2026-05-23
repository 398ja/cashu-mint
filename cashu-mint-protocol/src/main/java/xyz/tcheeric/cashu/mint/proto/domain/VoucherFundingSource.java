package xyz.tcheeric.cashu.mint.proto.domain;

/**
 * Discriminator for the three voucher funding variants (spec 003 FR-002).
 * Maps 1:1 to the {@code funding_source} column on {@code voucher_funding}
 * and to the three concrete {@code VoucherFundingEntity} subclasses.
 *
 * @see <a href="../../../../../../../../specs/003-voucher-quote-durability/data-model.md">spec 003 data-model § VoucherFunding</a>
 */
public enum VoucherFundingSource {
    /** Funded by a settled customer payment (e.g. NUT-04 mint quote payment). */
    CUSTOMER_PAYMENT,
    /** Funded by a debit against the merchant's ledger. */
    MERCHANT_DEBIT,
    /** Funded by an explicit merchant IOU (subject to per-profile policy). */
    MERCHANT_IOU
}
