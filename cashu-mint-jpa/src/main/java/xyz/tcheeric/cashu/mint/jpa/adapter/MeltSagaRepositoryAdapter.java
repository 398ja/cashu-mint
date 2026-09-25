package xyz.tcheeric.cashu.mint.jpa.adapter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.cashu.mint.jpa.entity.MeltSagaEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.MeltSagaTransitionEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.MeltSagaJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.MeltSagaTransitionJpaRepository;
import xyz.tcheeric.cashu.mint.proto.domain.MeltSagaState;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSaga;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSagaRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSagaTransition;

import java.util.List;
import java.util.Optional;

/**
 * JPA-backed implementation of {@link MeltSagaRepository}. Gated on
 * {@code cashu.mint.jpa.enabled=true} (same flag as the spec-001 adapters).
 */
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
public class MeltSagaRepositoryAdapter implements MeltSagaRepository {

    private final MeltSagaJpaRepository sagas;
    private final MeltSagaTransitionJpaRepository transitions;

    /**
     * This adapter through its own Spring proxy, so {@code REQUIRES_NEW} on
     * {@link #appendAtNextSequence} is honoured.
     *
     * <p>Calling it as {@code this.appendAtNextSequence(...)} would bypass the proxy entirely and the
     * propagation would be silently ignored: every retry would then run in the caller's transaction,
     * which a constraint violation has already marked rollback-only, so the retry could not commit and
     * the fix would look right while doing nothing.
     *
     * <p>{@code @Lazy} because the reference is to the bean being constructed.
     */
    private final MeltSagaRepositoryAdapter self;

    public MeltSagaRepositoryAdapter(MeltSagaJpaRepository sagas,
                                     MeltSagaTransitionJpaRepository transitions,
                                     @Lazy MeltSagaRepositoryAdapter self) {
        this.sagas = sagas;
        this.transitions = transitions;
        this.self = self;
    }

    /**
     * How many times to re-derive the sequence before giving up.
     *
     * <p>Each conflict means a competing writer committed, so an attempt only fails after the timeline
     * genuinely moved forward. The bound therefore has to cover the number of writers that can contend
     * at once, not just "a couple of retries": with 8 simultaneous appends, 3 attempts was measured to
     * exhaust itself and throw, because a loser can lose repeatedly while the others drain.
     *
     * <p>16 covers the contention that exists here (one request thread and one scheduled tick per
     * saga, plus the admin endpoint) with a wide margin, and stays bounded so that a pathologically
     * hot saga cannot hold a connection indefinitely.
     */
    private static final int SEQUENCE_CONFLICT_ATTEMPTS = 16;

    @Override
    public Optional<MeltSaga> findById(String meltSagaId) {
        return sagas.findById(meltSagaId).map(e -> (MeltSaga) e);
    }

    @Override
    public Optional<MeltSaga> findByQuoteId(String quoteId) {
        return sagas.findByQuoteId(quoteId).map(e -> (MeltSaga) e);
    }

    @Override
    public MeltSaga save(MeltSaga saga) {
        MeltSagaEntity entity = (saga instanceof MeltSagaEntity existing)
                ? existing
                : toEntity(saga);
        try {
            return sagas.save(entity);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            // Spec 002 FR-005 — concurrent melts for the same quote_id
            // can both pass MeltTask.findByQuoteId and race to save.
            // Re-throw with a marker the caller can translate to
            // melt_in_progress. (The caller in MeltTask catches DIVE
            // directly; this rethrow keeps the legacy behaviour for any
            // other callers + adapter implementations.)
            throw race;
        }
    }

    @Override
    public int casState(String meltSagaId, MeltSagaState expected, MeltSagaState target) {
        return sagas.casState(meltSagaId, expected, target);
    }

    /**
     * Appends a transition, retrying when another writer claims the same sequence number.
     *
     * <p>The sequence is derived with a read (`MAX(seq)`) followed by a write, and
     * {@code (melt_saga_id, seq)} is the composite primary key, so two writers that read the same
     * maximum build the same key and one of them loses its row. That is reachable in production
     * rather than only under test: a melt in flight writes transitions from the request thread while
     * {@code MeltSagaReconciler} writes them from its scheduled tick.
     *
     * <p>Losing one is worse than it sounds. {@code sweepStaleProofsHeld} deliberately records the
     * transition <em>before</em> refunding, because a refund with no audit row cannot be reconstructed
     * (#464). A silently dropped append defeats exactly that ordering.
     *
     * <p>Retrying is safe here specifically because the timeline is append-only and the row carries no
     * identity of its own: re-reading the maximum and inserting again produces the intended record at
     * the next free sequence. A caller cannot observe the difference, because the returned entity is
     * the one that committed.
     *
     * <p>Each attempt runs in its own transaction. A constraint violation marks a transaction
     * rollback-only, so retrying inside the failed one would fail again on commit no matter what the
     * insert did.
     */
    @Override
    public MeltSagaTransition recordTransition(String meltSagaId,
                                               MeltSagaState fromState,
                                               MeltSagaState toState,
                                               String reason,
                                               String actor) {
        DataIntegrityViolationException lastConflict = null;
        for (int attempt = 1; attempt <= SEQUENCE_CONFLICT_ATTEMPTS; attempt++) {
            try {
                return self.appendAtNextSequence(meltSagaId, fromState, toState, reason, actor);
            } catch (DataIntegrityViolationException conflict) {
                lastConflict = conflict;
            }
        }
        // Surfaced rather than swallowed. A caller that is about to move money on the strength of
        // this record must not be told the record exists when it does not.
        throw new IllegalStateException(
                "could not append a melt saga transition for " + meltSagaId + " after "
                        + SEQUENCE_CONFLICT_ATTEMPTS + " attempts: the timeline sequence stayed "
                        + "contended", lastConflict);
    }

    /**
     * One attempt: read the current maximum sequence and insert at the next one.
     *
     * <p>{@code REQUIRES_NEW} so a failed attempt's rollback-only marking dies with its own
     * transaction instead of poisoning the retry or the caller.
     */
    @Transactional(value = "mintTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public MeltSagaTransition appendAtNextSequence(String meltSagaId,
                                                   MeltSagaState fromState,
                                                   MeltSagaState toState,
                                                   String reason,
                                                   String actor) {
        int nextSeq = transitions.maxSeq(meltSagaId) + 1;
        MeltSagaTransitionEntity entry = new MeltSagaTransitionEntity();
        entry.setMeltSagaId(meltSagaId);
        entry.setSeq(nextSeq);
        entry.setFromState(fromState);
        entry.setToState(toState);
        entry.setReason(reason);
        entry.setActor(actor == null ? "system" : actor);
        // saveAndFlush, not save: save can defer the INSERT to commit, which raises the conflict
        // outside this method where the retry cannot see it.
        return transitions.saveAndFlush(entry);
    }

    @Override
    public List<MeltSagaTransition> transitionsFor(String meltSagaId) {
        return transitions.findTimeline(meltSagaId).stream()
                .map(e -> (MeltSagaTransition) e)
                .toList();
    }

    @Override
    public List<MeltSaga> findByState(MeltSagaState state) {
        return sagas.findByCurrentState(state).stream()
                .map(e -> (MeltSaga) e)
                .toList();
    }

    @Override
    public void updateResponseCache(String meltSagaId, String responseJson) {
        if (responseJson == null) {
            return;
        }
        sagas.updateResponseCache(meltSagaId, responseJson);
    }

    @Override
    public void updateProviderMetadata(String meltSagaId,
                                       String paymentHash,
                                       String providerEventId,
                                       String paymentOutcomeReason) {
        if (paymentHash == null && providerEventId == null && paymentOutcomeReason == null) {
            return;
        }
        sagas.updateProviderMetadata(meltSagaId, paymentHash, providerEventId, paymentOutcomeReason);
    }

    @Override
    public void updateChangeOutputs(String meltSagaId,
                                    String changeOutputsHash,
                                    String changeSignaturesJson) {
        if (changeOutputsHash == null && changeSignaturesJson == null) {
            return;
        }
        sagas.updateChangeOutputs(meltSagaId, changeOutputsHash, changeSignaturesJson);
    }

    private static MeltSagaEntity toEntity(MeltSaga s) {
        MeltSagaEntity e = new MeltSagaEntity();
        e.setMeltSagaId(s.meltSagaId());
        e.setQuoteId(s.quoteId());
        e.setInvoiceAmount(s.invoiceAmount());
        e.setExactFeeReserve(s.exactFeeReserve());
        e.setAssertedFeeReserve(s.assertedFeeReserve());
        e.setInputAmount(s.inputAmount());
        e.setProofCount(s.proofCount());
        e.setCurrentState(s.currentState());
        e.setPaymentHash(s.paymentHash());
        e.setProvider(s.provider());
        e.setProviderEventId(s.providerEventId());
        e.setPaymentOutcomeReason(s.paymentOutcomeReason());
        e.setMeltResponseCache(s.meltResponseCache());
        e.setChangeOutputsHash(s.changeOutputsHash());
        e.setChangeSignaturesJson(s.changeSignaturesJson());
        return e;
    }
}
