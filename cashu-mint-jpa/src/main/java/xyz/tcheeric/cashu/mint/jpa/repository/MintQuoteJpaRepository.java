package xyz.tcheeric.cashu.mint.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.cashu.mint.jpa.entity.MintQuoteEntity;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;

/**
 * Spring Data JPA backing for {@code mint_quote}. The single non-derived query
 * is {@link #casLifecycle}, which compiles to a conditional UPDATE so that
 * concurrent writers cannot both succeed in advancing the lifecycle state.
 *
 * <p>Spec 001: research §R3 (compare-and-set).
 *
 * <h2>Operator reconciliation (FR-001 daily invariant)</h2>
 *
 * Run this query daily and alert if it does not return 0 rows. The total
 * authorised amount across {@code ISSUED} quotes MUST equal the total amount
 * issued in append-only {@code issuance_record} rows:
 *
 * <pre>{@code
 * SELECT 'mint_quote_vs_issuance_record' AS check_name,
 *        COALESCE((SELECT SUM(mq.amount)
 *                  FROM mint_quote mq
 *                  WHERE mq.lifecycle_state = 'ISSUED'), 0) AS issued_quote_total,
 *        COALESCE((SELECT SUM(ir.total_amount) FROM issuance_record ir), 0) AS issued_record_total
 * HAVING COALESCE((SELECT SUM(mq.amount)
 *                  FROM mint_quote mq
 *                  WHERE mq.lifecycle_state = 'ISSUED'), 0)
 *       <> COALESCE((SELECT SUM(ir.total_amount) FROM issuance_record ir), 0);
 * }</pre>
 *
 * A second invariant: every {@code PAID → ISSUED} transition MUST have a
 * matching {@code webhook_event} row with {@code outcome = 'accepted'}:
 *
 * <pre>{@code
 * SELECT mq.quote_id
 *   FROM mint_quote mq
 *   LEFT JOIN webhook_event we
 *     ON we.quote_id = mq.quote_id AND we.outcome = 'accepted'
 *  WHERE mq.lifecycle_state IN ('PAID','ISSUING','ISSUED')
 *    AND we.quote_id IS NULL;
 * }</pre>
 *
 * Either query returning non-empty is an operator alert per spec 001 SC-001 /
 * SC-004.
 */
@Repository
public interface MintQuoteJpaRepository extends JpaRepository<MintQuoteEntity, String> {

    /**
     * Conditional UPDATE on {@code lifecycle_state}: only sets {@code to} when
     * the current value is {@code from}. Returns {@code 1} on success, {@code 0}
     * if a concurrent writer already advanced the state. Callers MUST re-read
     * on a return of {@code 0} and decide whether to replay or reject.
     */
    @Modifying
    @Transactional("mintTransactionManager")
    @Query("UPDATE MintQuoteEntity q "
            + "SET q.lifecycleState = :to, q.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE q.quoteId = :id AND q.lifecycleState = :from")
    int casLifecycle(@Param("id") String id,
                     @Param("from") LifecycleState from,
                     @Param("to") LifecycleState to);
}
