package xyz.tcheeric.cashu.mint.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuoteEntity;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;

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
}
