package xyz.tcheeric.cashu.mint.proto.ports;

import xyz.tcheeric.cashu.mint.proto.domain.MeltSagaState;

import java.util.List;
import java.util.Optional;

/**
 * Port used by {@code MeltTask} and {@code MeltSagaReconciler} to read and
 * transition durable melt-saga state. Implementations live in
 * {@code cashu-mint-jpa}.
 *
 * <p>Spec 002 FR-004 / FR-005: state transitions are compare-and-set
 * conditional UPDATEs, mirroring the spec-001
 * {@link MintQuoteRepository#casLifecycle} idiom. Transition rows are
 * append-only via {@link #recordTransition}.
 */
public interface MeltSagaRepository {

    Optional<MeltSaga> findById(String meltSagaId);

    Optional<MeltSaga> findByQuoteId(String quoteId);

    /**
     * Inserts a new saga. The implementation MUST surface
     * {@code quote_id} uniqueness violations so the caller can return the
     * {@code melt_in_progress} response (FR-005).
     */
    MeltSaga save(MeltSaga saga);

    /**
     * Atomically transitions {@code current_state} from {@code expected} to
     * {@code target}. Returns {@code 1} on success, {@code 0} if a
     * concurrent writer already advanced the state.
     */
    int casState(String meltSagaId, MeltSagaState expected, MeltSagaState target);

    /** Appends an immutable timeline entry. The implementation chooses {@code seq}. */
    MeltSagaTransition recordTransition(String meltSagaId,
                                        MeltSagaState fromState,
                                        MeltSagaState toState,
                                        String reason,
                                        String actor);

    /** Loads the full ordered timeline for the admin query endpoint (US3). */
    List<MeltSagaTransition> transitionsFor(String meltSagaId);

    /** Returns sagas currently in the given state (for reconciliation sweeps). */
    List<MeltSaga> findByState(MeltSagaState state);

    /**
     * Spec 002 T216 / research R7 — persist the serialised terminal response
     * on a saga for NUT-19 cached-response replay. Callers invoke this on
     * {@code COMPLETED}, {@code FAILED}, and {@code PAYMENT_SENT_BURN_FAILED}.
     * Idempotent: a non-null cache overwrites; null is a no-op.
     */
    void updateResponseCache(String meltSagaId, String responseJson);
}
