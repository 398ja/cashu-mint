package xyz.tcheeric.cashu.mint.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.cashu.mint.jpa.entity.MeltSagaEntity;
import xyz.tcheeric.cashu.mint.proto.domain.MeltSagaState;

import java.util.List;
import java.util.Optional;

/**
 * Spec 002 — durable melt-saga rows. The single non-derived query is
 * {@link #casState}, mirroring the spec-001
 * {@code MintQuoteJpaRepository#casLifecycle} idiom: a row-count of 0 means
 * a concurrent writer already advanced the state.
 *
 * <h2>Operator reconciliation (spec 002 SC-002 / SC-003)</h2>
 *
 * <pre>{@code
 * -- SC-002: every COMPLETED saga must have all referenced proofs in SPENT.
 * --         The mint side records 0 PROOF rows itself (the vault owns the
 * --         proof table); this query lives in the cashu-vault project once
 * --         the proof_entity.melt_saga_id column lands. Track the gap here:
 * SELECT s.melt_saga_id FROM melt_saga s WHERE s.current_state = 'COMPLETED';
 *
 * -- SC-003: PROOFS_HELD precedes PAYMENT_SENT for every saga.
 * SELECT m.melt_saga_id
 *   FROM melt_saga m
 *   JOIN melt_saga_transition t1 ON t1.melt_saga_id = m.melt_saga_id
 *                              AND t1.to_state = 'PROOFS_HELD'
 *   JOIN melt_saga_transition t2 ON t2.melt_saga_id = m.melt_saga_id
 *                              AND t2.to_state = 'PAYMENT_SENT'
 *  WHERE t1.seq >= t2.seq;
 * -- expected: 0 rows
 * }</pre>
 */
@Repository
public interface MeltSagaJpaRepository extends JpaRepository<MeltSagaEntity, String> {

    Optional<MeltSagaEntity> findByQuoteId(String quoteId);

    List<MeltSagaEntity> findByCurrentState(MeltSagaState state);

    @Modifying
    @Transactional("mintTransactionManager")
    @Query("UPDATE MeltSagaEntity s "
            + "SET s.currentState = :to, s.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE s.meltSagaId = :id AND s.currentState = :from")
    int casState(@Param("id") String id,
                 @Param("from") MeltSagaState from,
                 @Param("to") MeltSagaState to);
}
