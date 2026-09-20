package xyz.tcheeric.cashu.mint.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuoteEntity;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spec 003 — Spring Data JPA backing for {@code voucher_quote}. CAS
 * transitions on {@code lifecycle_state} mirror the spec-001 idiom;
 * {@link #attachFundingAndAdvance} folds the funding-attach +
 * {@code UNFUNDED → FUNDED} transition into one conditional UPDATE
 * so no half-state is visible to readers.
 *
 * <h2>Operator reconciliation (SC-001 daily invariant)</h2>
 *
 * Run this query daily and alert if it returns rows — every issued
 * voucher MUST trace to a funding row:
 *
 * <pre>{@code
 * SELECT q.quote_id
 *   FROM voucher_quote q
 *  WHERE q.lifecycle_state = 'ISSUED'
 *    AND q.funding_id IS NULL;
 * }</pre>
 */
@Repository
public interface VoucherQuoteJpaRepository extends JpaRepository<VoucherQuoteEntity, String> {

    Optional<VoucherQuoteEntity> findByIdempotencyKey(String idempotencyKey);

    /**
     * Spec 004 FR-009 — operator forensic lookup. Native query bypasses
     * the IdentityHashConverter (which would otherwise hash the param
     * on its way to the WHERE clause, double-hashing the operator's
     * pre-computed lookup value).
     */
    @Query(value = "SELECT * FROM voucher_quote WHERE customer_id = :hash",
            nativeQuery = true)
    java.util.List<VoucherQuoteEntity> findByCustomerIdHash(@Param("hash") String customerIdHash);

    @Query(value = "SELECT * FROM voucher_quote WHERE merchant_id = :hash",
            nativeQuery = true)
    java.util.List<VoucherQuoteEntity> findByMerchantIdHash(@Param("hash") String merchantIdHash);

    /**
     * Conditional UPDATE on {@code lifecycle_state}: only sets {@code to}
     * when the current value is {@code from}. Returns {@code 1} on
     * success, {@code 0} if a concurrent writer already advanced state.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional("mintTransactionManager")
    @Query("UPDATE VoucherQuoteEntity q "
            + "SET q.lifecycleState = :to, q.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE q.quoteId = :id AND q.lifecycleState = :from")
    int casLifecycle(@Param("id") String id,
                     @Param("from") VoucherLifecycleState from,
                     @Param("to") VoucherLifecycleState to);

    /**
     * Conditional UPDATE that attaches {@code funding_id} and advances
     * {@code UNFUNDED → FUNDED} atomically. Eliminates the half-state
     * where a row has funding_id set but lifecycle is still UNFUNDED.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional("mintTransactionManager")
    @Query("UPDATE VoucherQuoteEntity q "
            + "SET q.fundingId = :fundingId, "
            + "    q.lifecycleState = xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState.FUNDED, "
            + "    q.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE q.quoteId = :id "
            + "  AND q.lifecycleState = xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState.UNFUNDED")
    int attachFundingAndAdvance(@Param("id") String id,
                                @Param("fundingId") String fundingId);

    /**
     * Spec 035 — atomic ISSUING → ISSUED CAS that ALSO sets
     * {@code original_token_amount}. Combining the lifecycle close and
     * the amount capture into one UPDATE prevents a half-state where a
     * row is ISSUED with NULL amount (which would mis-flag it as
     * pre-migration legacy and silently disable the wallet's
     * partial-spend correction for that voucher).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional("mintTransactionManager")
    @Query("UPDATE VoucherQuoteEntity q "
            + "SET q.lifecycleState = xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState.ISSUED, "
            + "    q.originalTokenAmount = :amount, "
            + "    q.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE q.quoteId = :id "
            + "  AND q.lifecycleState = xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState.ISSUING")
    int recordIssuance(@Param("id") String id,
                       @Param("amount") long originalTokenAmount);

    /**
     * Issue #459 — the reconciler sweep. Voucher quotes still {@code UNFUNDED}
     * that already have an {@code accepted} {@code webhook_event}: the customer
     * paid and the mint recorded it, but no funding row was ever attached.
     *
     * <p><strong>This is the query shape that distinguishes a swept machine
     * from a stranded one</strong> (#461). A sweep is time-bounded and finds
     * rows it was not told about; a client flow already knows which row it is
     * asking about, which is exactly why the request path could never have
     * recovered these.
     *
     * <p>{@code receivedBefore} is a grace period, not an age filter: half A
     * attaches funding in the webhook transaction, so a quote paid moments ago
     * is mid-flight rather than stranded. Bounding on the event's
     * {@code received_at} (not the quote's {@code created_at}) measures time
     * since the money arrived, which is the duration that matters — a quote
     * created last week and paid ten seconds ago is not stranded.
     */
    @Query(nativeQuery = true, value = """
            SELECT q.* FROM voucher_quote q
             WHERE q.lifecycle_state = 'UNFUNDED'
               AND EXISTS (
                   SELECT 1 FROM webhook_event e
                    WHERE e.quote_id = q.quote_id
                      AND e.outcome = 'accepted'
                      AND e.received_at < :receivedBefore
               )
             ORDER BY q.created_at
             LIMIT :batchSize
            """)
    List<VoucherQuoteEntity> findPaidButUnfunded(@Param("receivedBefore") Instant receivedBefore,
                                                 @Param("batchSize") int batchSize);

    /**
     * Issue #459 / ADR 0002 — the Paid-Unfunded invariant, exported as a
     * DB-derived gauge by {@code InvariantGaugePoller}.
     *
     * <p>Non-zero means the mint has taken money it has not issued against.
     * There is no benign instance of this: every row is a customer who paid
     * and received nothing. Deliberately unbounded by time — the gauge counts
     * the standing liability, while {@link #findPaidButUnfunded} applies the
     * grace period so the sweep does not race half A.
     */
    @Query(nativeQuery = true, value = """
            SELECT count(*) FROM voucher_quote q
             WHERE q.lifecycle_state = 'UNFUNDED'
               AND EXISTS (
                   SELECT 1 FROM webhook_event e
                    WHERE e.quote_id = q.quote_id
                      AND e.outcome = 'accepted'
               )
            """)
    long countPaidUnfunded();

    /**
     * Issue #459 / #462 — the blind spot between the two. Voucher quotes still
     * {@code UNFUNDED} with <strong>no {@code webhook_event} row at all</strong>.
     *
     * <p>{@link #countPaidUnfunded} deliberately requires an {@code accepted}
     * webhook before it will call a quote stranded, because within the mint
     * that event is the only evidence money changed hands. That is the right
     * predicate for a gauge that claims "the mint took money" — but it means a
     * payment the mint was never told about is invisible to it, and equally
     * invisible to {@link #findPaidButUnfunded}, which carries the same clause.
     * The reconciler cannot resolve these and must not: minting against a
     * payment the mint has no record of is exactly the failure it exists to
     * prevent.
     *
     * <p>Observed on staging 2026-09-20: 68 {@code UNFUNDED} quotes, 62 with an
     * accepted webhook, 6 with none — and all 6 were {@code PAID} at the
     * payment adapter. Those 6 are recoverable only by re-delivery from the
     * adapter side (#462), never by the sweep.
     *
     * <p>Non-zero here is therefore not "the mint has a problem" but "the mint
     * and the adapter disagree, and the mint cannot see which side is right".
     * It is a prompt to run the adapter-side cross-check, not to mint anything.
     */
    @Query(nativeQuery = true, value = """
            SELECT count(*) FROM voucher_quote q
             WHERE q.lifecycle_state = 'UNFUNDED'
               AND NOT EXISTS (
                   SELECT 1 FROM webhook_event e
                    WHERE e.quote_id = q.quote_id
               )
            """)
    long countUnfundedWithoutWebhook();

    /**
     * Issue #459 / #462 — the third bucket, and the one that is easiest to
     * miss because it looks like it cannot exist.
     *
     * <p>{@code outcome} has eleven values. {@link #countPaidUnfunded} counts
     * quotes with an {@code accepted} event, {@link #countUnfundedWithoutWebhook}
     * counts quotes with no event at all, and a quote whose only events were
     * <em>rejected</em> falls between them: it has rows, so it is not
     * "without webhook", but none are {@code accepted}, so it is not
     * "paid-unfunded" either. Two gauges over eleven outcomes do not
     * partition anything.
     *
     * <p>Staging has only ever recorded {@code accepted} (130 of 130 events as
     * of 2026-09-20), so this reads zero there and the omission would not have
     * shown up on any dashboard. That is a property of the current data, not
     * of the design.
     *
     * <p>Non-zero means the mint saw a payment event for a known quote and
     * refused it — {@code amount_mismatch}, {@code unit_mismatch},
     * {@code tamper}, {@code expired}. Unlike the other two, this one is not
     * ambiguous about whether the mint was told: it was, and it said no. The
     * outcome column says why, and whether the customer is owed anything
     * depends on which outcome it was, so there is no single remedy to
     * automate.
     */
    @Query(nativeQuery = true, value = """
            SELECT count(*) FROM voucher_quote q
             WHERE q.lifecycle_state = 'UNFUNDED'
               AND EXISTS (
                   SELECT 1 FROM webhook_event e
                    WHERE e.quote_id = q.quote_id
               )
               AND NOT EXISTS (
                   SELECT 1 FROM webhook_event e
                    WHERE e.quote_id = q.quote_id
                      AND e.outcome = 'accepted'
               )
            """)
    long countUnfundedRejectedOnly();
}
