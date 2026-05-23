package xyz.tcheeric.cashu.mint.proto.ports;

import java.util.Optional;

/**
 * Spec 003 — port to the durable {@code voucher_funding} table (parent of
 * the JOINED inheritance hierarchy). Mostly read by the resolver and
 * the audit query; writes happen on three paths:
 *
 * <ul>
 *   <li>The spec-001 webhook bridge inserts a
 *       {@code CustomerPaymentFunding} when a settled payment lands for
 *       a voucher quote in {@code UNFUNDED}.</li>
 *   <li>The merchant-debit API (out of scope for v1) inserts a
 *       {@code MerchantDebitFunding}.</li>
 *   <li>An operator endpoint inserts a {@code MerchantIouFunding} when
 *       the deployment policy permits IOUs.</li>
 * </ul>
 */
public interface VoucherFundingRepository {

    Optional<VoucherFunding> findById(String fundingId);

    /** Inserts (or updates, for the {@code @Version} field) a funding row. */
    VoucherFunding save(VoucherFunding funding);

    /**
     * Looks up a {@code CustomerPaymentFunding} by its provider event id.
     * Backs the spec-001 webhook idempotency check (a webhook replay
     * MUST NOT insert a second funding row for the same provider event).
     */
    Optional<VoucherFunding> findByProviderEventId(String provider, String providerEventId);
}
