package xyz.tcheeric.cashu.mint.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherIssuanceEntity;

/**
 * Spec 003 — append-only ledger of voucher issuances. Inserts go through
 * the application's insert-if-absent helper in the adapter to keep the
 * NUT-19 idempotent replay guarantee.
 *
 * <h2>Operator queries (embedded as Javadoc per spec 003 T902)</h2>
 *
 * <pre>{@code
 * -- SC-001: every issued voucher traces to a funding row
 * SELECT q.quote_id FROM voucher_quote q
 *  WHERE q.lifecycle_state = 'ISSUED'
 *    AND q.funding_id IS NULL;
 * -- expected: 0 rows
 *
 * -- Operator IOU liability dashboard
 * SELECT f.funding_source, SUM(f.amount)
 * FROM voucher_funding f
 * JOIN voucher_quote q ON q.funding_id = f.funding_id
 *  AND q.lifecycle_state = 'ISSUED'
 * GROUP BY f.funding_source;
 * }</pre>
 */
@Repository
public interface VoucherIssuanceJpaRepository extends JpaRepository<VoucherIssuanceEntity, String> {
}
