package xyz.tcheeric.cashu.mint.proto.ports;

import xyz.tcheeric.cashu.mint.proto.domain.VoucherFundingSource;

import java.util.Optional;

/**
 * Spec 003 — encapsulates the policy that decides whether a voucher
 * quote is currently funded. Three concrete strategies are layered
 * behind one port (research R6):
 *
 * <ul>
 *   <li>{@link VoucherFundingSource#CUSTOMER_PAYMENT} — look up the
 *       spec-001 {@code WebhookEvent} for the quote_id; if accepted,
 *       a {@code CustomerPaymentFundingEntity} either already exists
 *       (webhook bridge inserted it) or is created lazily here.</li>
 *   <li>{@link VoucherFundingSource#MERCHANT_DEBIT} — look up the merchant
 *       ledger by {@code merchantId + chargedAmount}; if a debit row
 *       exists, return the matching funding.</li>
 *   <li>{@link VoucherFundingSource#MERCHANT_IOU} — only when the
 *       deployment policy says {@code ALLOW}; emits an operator alert
 *       on every issuance regardless of policy (FR-014).</li>
 * </ul>
 *
 * <p>{@link #resolveForQuote} returns the funding row to attach, or
 * empty if none applies. The caller ({@code MintTask} voucher branch)
 * decides whether empty means "wait, funding might land later" or
 * "reject with funding_required" based on the lifecycle state.
 */
public interface VoucherFundingResolver {

    Optional<VoucherFunding> resolveForQuote(VoucherQuote quote);
}
