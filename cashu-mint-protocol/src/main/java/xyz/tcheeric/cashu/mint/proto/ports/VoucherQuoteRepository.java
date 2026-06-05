package xyz.tcheeric.cashu.mint.proto.ports;

import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;

import java.util.List;
import java.util.Optional;

/**
 * Spec 003 — port used by {@code VoucherMintQuoteTask} and the voucher
 * branch of {@code MintTask} to read and transition durable voucher
 * quote state. Mirrors {@link MintQuoteRepository} for the regular
 * NUT-04 path; the CAS-on-lifecycle idiom is identical.
 *
 * <p>Implemented by the JPA adapter in {@code cashu-mint-jpa}.
 */
public interface VoucherQuoteRepository {

    Optional<VoucherQuote> findById(String quoteId);

    /**
     * Inserts or updates a voucher quote row. Insert is the normal path
     * (quote creation); update is used for non-lifecycle field changes
     * (e.g. attaching a {@code funding_id} after the funding row commits).
     */
    VoucherQuote save(VoucherQuote quote);

    /**
     * Atomically transitions {@code lifecycle_state} from {@code expected}
     * to {@code target}. Returns {@code 1} on success, {@code 0} if a
     * concurrent writer advanced the state first. Callers MUST treat
     * {@code 0} as "lost the race; re-read and decide".
     */
    int casLifecycle(String quoteId, VoucherLifecycleState expected, VoucherLifecycleState target);

    /**
     * Spec 035 — atomic ISSUING → ISSUED transition that ALSO records
     * the original sat-denominated proof sum. Single conditional UPDATE
     * so the lifecycle close and the {@code original_token_amount}
     * capture commit together (no half-state where a row is ISSUED but
     * still has {@code NULL} amount, which would mis-flag it as legacy).
     *
     * <p>{@code originalTokenAmount} MUST be {@code > 0}; the provenance
     * endpoint downstream returns {@code issuance_ratio: null} for
     * non-positive values per the documented contract.
     *
     * @return {@code 1} on success, {@code 0} if the row was not in
     *         ISSUING (already issued, expired, or missing).
     */
    int recordIssuance(String quoteId, long originalTokenAmount);

    /**
     * Atomically attaches a {@code funding_id} to a row currently in
     * {@code UNFUNDED}, advancing the state to {@code FUNDED}. Single
     * conditional UPDATE so the funding-attach + state-transition are
     * applied together (no half-state visible to readers).
     *
     * @return 1 on success, 0 if the row was not in UNFUNDED (already
     *         funded, expired, or missing)
     */
    int attachFundingAndAdvance(String quoteId, String fundingId);

    /**
     * Optional lookup by client-supplied {@code idempotency_key}. Used by
     * the idempotency middleware to detect retries before allocating a
     * new voucher quote id (FR-009).
     */
    Optional<VoucherQuote> findByIdempotencyKey(String idempotencyKey);

    /**
     * Spec 004 FR-009 — operator forensic lookup. Returns voucher
     * quotes where {@code customer_id} matches the given hashed
     * value. Empty list if no match (including the post-retention
     * case where the column was nullified).
     */
    List<VoucherQuote> findByCustomerIdHash(String customerIdHash);

    /**
     * Spec 004 FR-009 — operator forensic lookup. Same shape as
     * {@link #findByCustomerIdHash} but keyed on {@code merchant_id}.
     */
    List<VoucherQuote> findByMerchantIdHash(String merchantIdHash);
}
