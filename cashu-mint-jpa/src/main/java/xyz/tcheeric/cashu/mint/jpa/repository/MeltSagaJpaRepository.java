package xyz.tcheeric.cashu.mint.jpa.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.cashu.mint.jpa.entity.MeltSagaEntity;
import xyz.tcheeric.cashu.mint.proto.domain.MeltSagaState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spec 002 — durable melt-saga rows. The single non-derived query is
 * {@link #casState}, mirroring the spec-001
 * {@code MintQuoteJpaRepository#casLifecycle} idiom: a row-count of 0 means
 * a concurrent writer already advanced the state.
 *
 * <h2>Operator reconciliation (spec 002 SC-001 / SC-002 / SC-003 / SC-004)</h2>
 *
 * <p>These queries are intended for the operator dashboard. Run daily and
 * alert if any returns a non-empty result.
 *
 * <pre>{@code
 * -- SC-001: 100% of under-funded melts are rejected before any external
 * --         payment. The mint records the rejection via a Micrometer
 * --         counter (cashu_mint_melt_insufficient_input_total); under-funded
 * --         requests do not even create a saga row, so the dashboard
 * --         assertion is "counter > 0 implies all observed rejections
 * --         emitted no gateway.pay log lines". The DB-level guard:
 * SELECT 1 FROM melt_saga s WHERE s.input_amount < s.invoice_amount + s.exact_fee_reserve;
 * -- expected: 0 rows (no saga should EVER pass BurnAmountValidator with
 * --           a sum < required; if any does, the validator has been bypassed)
 *
 * -- SC-002: every COMPLETED saga must have all referenced proofs in SPENT.
 * --         The mint side does not own the proof table; this query lives
 * --         in the cashu-vault project once the proof_entity.melt_saga_id
 * --         column lands (cross-repo dependency). Mint-side proxy:
 * SELECT s.melt_saga_id
 *   FROM melt_saga s
 *  WHERE s.current_state = 'COMPLETED'
 *    AND s.melt_response_cache IS NULL;
 * -- expected: 0 rows (T216 contract: COMPLETED ⇒ response cached)
 *
 * -- SC-003: PROOFS_HELD precedes PAYMENT_SENT for every saga that reached
 * --         external payment.
 * SELECT m.melt_saga_id
 *   FROM melt_saga m
 *   JOIN melt_saga_transition t1 ON t1.melt_saga_id = m.melt_saga_id
 *                              AND t1.to_state = 'PROOFS_HELD'
 *   JOIN melt_saga_transition t2 ON t2.melt_saga_id = m.melt_saga_id
 *                              AND t2.to_state = 'PAYMENT_SENT'
 *  WHERE t1.seq >= t2.seq;
 * -- expected: 0 rows
 *
 * -- SC-004: PAYMENT_UNKNOWN sagas must not silently auto-settle. Either
 * --         the reconciler advances them (poll transition rows appear)
 * --         OR an operator alert has fired (TTL exceeded). A saga in
 * --         PAYMENT_UNKNOWN with no poll transitions and age > TTL is
 * --         an alerting condition:
 * SELECT s.melt_saga_id, s.created_at, now() - s.created_at AS age
 *   FROM melt_saga s
 *  WHERE s.current_state = 'PAYMENT_UNKNOWN'
 *    AND s.created_at < now() - INTERVAL '1 hour'
 *    AND NOT EXISTS (
 *        SELECT 1 FROM melt_saga_transition t
 *         WHERE t.melt_saga_id = s.melt_saga_id
 *           AND t.actor = 'poll'
 *           AND t.at > now() - INTERVAL '5 minutes'
 *      );
 * -- expected: 0 rows (reconciler should be polling these)
 *
 * -- Hygiene: PAYMENT_SENT_BURN_FAILED sagas requiring operator review
 * SELECT s.melt_saga_id, s.quote_id, s.created_at,
 *        (SELECT count(*) FROM melt_saga_transition t
 *          WHERE t.melt_saga_id = s.melt_saga_id
 *            AND t.actor LIKE 'operator:%') AS operator_actions
 *   FROM melt_saga s
 *  WHERE s.current_state = 'PAYMENT_SENT_BURN_FAILED'
 *  ORDER BY s.created_at;
 * -- expected: review queue; never auto-resolved (FR-007 / FR-011)
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

    @Modifying
    @Transactional("mintTransactionManager")
    @Query("UPDATE MeltSagaEntity s "
            + "SET s.meltResponseCache = :json, s.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE s.meltSagaId = :id")
    int updateResponseCache(@Param("id") String id, @Param("json") String json);

    /**
     * Spec 002 — set the provider identifiers on a saga row. {@code COALESCE}
     * preserves the existing value when the caller passes {@code null} for
     * a field (used so the Success-branch update doesn't overwrite a
     * payment_hash recorded by an earlier PAYMENT_SENT transition).
     */
    @Modifying
    @Transactional("mintTransactionManager")
    @Query("UPDATE MeltSagaEntity s "
            + "SET s.paymentHash = COALESCE(:paymentHash, s.paymentHash), "
            + "    s.providerEventId = COALESCE(:providerEventId, s.providerEventId), "
            + "    s.paymentOutcomeReason = COALESCE(:reason, s.paymentOutcomeReason), "
            + "    s.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE s.meltSagaId = :id")
    int updateProviderMetadata(@Param("id") String id,
                               @Param("paymentHash") String paymentHash,
                               @Param("providerEventId") String providerEventId,
                               @Param("reason") String paymentOutcomeReason);

    /** Spec 002 T215 / FR-013 — persist NUT-08 change-return forensics columns. */
    @Modifying
    @Transactional("mintTransactionManager")
    @Query("UPDATE MeltSagaEntity s "
            + "SET s.changeOutputsHash = :hash, "
            + "    s.changeSignaturesJson = :json, "
            + "    s.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE s.meltSagaId = :id")
    int updateChangeOutputs(@Param("id") String id,
                            @Param("hash") String changeOutputsHash,
                            @Param("json") String changeSignaturesJson);

    /**
     * Issue #344 / ADR 0002 — Stuck Payment invariant, exported as a
     * DB-derived gauge by {@code InvariantGaugePoller}: melt sagas that have
     * sat in {@code PAYMENT_UNKNOWN} for longer than
     * {@code cashu.mint.melt.payment-unknown-ttl} without an operator looking
     * at them.
     *
     * <p><strong>Time in state, not age of saga.</strong> The boundary is
     * compared against the last transition <em>into</em>
     * {@code PAYMENT_UNKNOWN}, not {@code created_at}: a saga created two
     * hours ago that only became ambiguous thirty seconds ago has not been
     * stuck for two hours. Reconciler poll no-ops (which record
     * {@code PAYMENT_UNKNOWN → PAYMENT_UNKNOWN}) are excluded from that
     * lookup, or every poll would reset the clock and the gauge could never
     * rise. {@code updated_at} is not used because metadata writes such as
     * {@link #updateProviderMetadata} bump it without any state change.
     *
     * <p><strong>Operator acknowledgement clears the gauge.</strong> Sagas
     * with an {@code actor LIKE 'operator:%'} transition are excluded — the
     * same clause the hygiene query above uses to tell reviewed rows from
     * unreviewed ones. This is the only way the alert can be cleared:
     * {@code MeltSagaAdminController#markResolved} deliberately appends a
     * transition without overwriting {@code current_state} (FR-007 / FR-011),
     * so a gauge keyed on state alone would page forever after an operator
     * had already settled the payment out of band.
     *
     * <p><strong>Divergence from the SC-004 block above.</strong> That query
     * also excludes sagas with an {@code actor='poll'} transition in the last
     * five minutes, which makes it a <em>reconciler-liveness</em> check rather
     * than a stuck-payment one: {@link xyz.tcheeric.cashu.mint.jpa.MeltSagaReconciler}
     * appends a poll row on every tick for exactly the sagas whose provider
     * status is still {@code Unknown}, so keeping that clause would pin the
     * gauge at zero while the reconciler was alive.
     */
    @Query(nativeQuery = true, value = """
            SELECT count(*)
            FROM melt_saga s
            WHERE s.current_state = 'PAYMENT_UNKNOWN'
              AND NOT EXISTS (
                  SELECT 1 FROM melt_saga_transition t
                  WHERE t.melt_saga_id = s.melt_saga_id
                    AND t.actor LIKE 'operator:%'
              )
              AND COALESCE((
                  SELECT max(t.at) FROM melt_saga_transition t
                  WHERE t.melt_saga_id = s.melt_saga_id
                    AND t.to_state = 'PAYMENT_UNKNOWN'
                    AND (t.from_state IS NULL OR t.from_state <> 'PAYMENT_UNKNOWN')
              ), s.created_at) < :ttlBoundary
            """)
    long countStuckPaymentUnknown(@Param("ttlBoundary") Instant ttlBoundary);

    /**
     * Issue #345 / ADR 0002 — {@code PAYMENT_SENT_BURN_FAILED} sagas awaiting
     * operator review, exported as a DB-derived gauge by
     * {@code InvariantGaugePoller}.
     *
     * <p>Every row means a payment settled while its proofs stayed spendable —
     * direct loss, with no benign instance, which is why the alert has no
     * sustain period. The {@code actor LIKE 'operator:%'} exclusion is the
     * hygiene query's own definition of "reviewed", and is what lets the page
     * clear: nothing moves a saga out of {@code PAYMENT_SENT_BURN_FAILED}
     * (FR-007 / FR-011 forbid auto-resolution), so a gauge keyed on state
     * alone would page for the lifetime of the row.
     */
    @Query(nativeQuery = true, value = """
            SELECT count(*)
            FROM melt_saga s
            WHERE s.current_state = 'PAYMENT_SENT_BURN_FAILED'
              AND NOT EXISTS (
                  SELECT 1 FROM melt_saga_transition t
                  WHERE t.melt_saga_id = s.melt_saga_id
                    AND t.actor LIKE 'operator:%'
              )
            """)
    long countPaymentSentBurnFailed();
}
