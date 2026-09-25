package xyz.tcheeric.cashu.mint.jpa.adapter;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import xyz.tcheeric.cashu.mint.jpa.entity.MeltSagaTransitionEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.MeltSagaJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.MeltSagaTransitionJpaRepository;
import xyz.tcheeric.cashu.mint.proto.domain.MeltSagaState;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSagaTransition;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Retry contract for the melt saga timeline append (#478).
 *
 * <p>{@code recordTransition} derives its sequence with a read of {@code MAX(seq)} followed by an
 * insert, and {@code (melt_saga_id, seq)} is the composite primary key, so two writers that read the
 * same maximum build the same key and one insert is rejected. That is reachable in production: a melt
 * in flight appends from the request thread while {@code MeltSagaReconciler} appends from its
 * scheduled tick.
 *
 * <p>Losing an append matters because {@code sweepStaleProofsHeld} deliberately records the transition
 * <em>before</em> refunding, on the grounds that a refund with no audit row cannot be reconstructed
 * (#464). A silently dropped append defeats that ordering.
 *
 * <p>Driven with a stubbed repository rather than a database. The behaviour under test is "what does
 * this method do when the insert is rejected", and a stub reproduces that deterministically. An
 * earlier attempt used a real Postgres container with eight threads; it did detect the bug, but it
 * also had to seed sagas into the shared schema that every other IT class in the module sees, and
 * {@code MeltSagaReconciler}'s sweep selects globally on {@code PROOFS_HELD}, so it perturbed
 * unrelated tests. A deterministic unit test is both a better detector and a smaller blast radius.
 */
class MeltSagaTimelineAppendRetryTest {

    private static final String SAGA = "saga-append-retry";

    /**
     * A conflicting insert must be retried, not surfaced, because the append is recoverable: the
     * timeline is append-only and the row carries no identity of its own, so re-deriving the sequence
     * and inserting again produces the intended record.
     */
    @Test
    void anAppendThatLosesTheSequenceRaceIsRetriedUntilItPersists() {
        RejectingTransitions transitions = new RejectingTransitions(2);
        MeltSagaRepositoryAdapter adapter = adapterOver(transitions);

        MeltSagaTransition appended = adapter.recordTransition(
                SAGA, MeltSagaState.PROOFS_HELD, MeltSagaState.FAILED, "proofs_held_ttl_expired", "sweep");

        assertThat(appended).as("the append must succeed once a free sequence is found").isNotNull();
        assertThat(transitions.attempts)
                .as("two rejections then a success is three attempts")
                .isEqualTo(3);
        assertThat(transitions.saved)
                .as("exactly one row is persisted, not one per attempt")
                .hasSize(1);
        assertThat(transitions.saved.get(0).getActor()).isEqualTo("sweep");
    }

    /**
     * The other direction: a contended sequence that never clears must throw rather than return
     * quietly.
     *
     * <p>A caller about to move money on the strength of this record must not be told the record
     * exists when it does not, which is the whole point of the audit-before-refund ordering.
     */
    @Test
    void anAppendThatNeverWinsTheRaceThrowsRatherThanReportingSuccess() {
        RejectingTransitions alwaysConflicts = new RejectingTransitions(Integer.MAX_VALUE);
        MeltSagaRepositoryAdapter adapter = adapterOver(alwaysConflicts);

        assertThatThrownBy(() -> adapter.recordTransition(
                SAGA, MeltSagaState.PROOFS_HELD, MeltSagaState.FAILED, "reason", "sweep"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SAGA)
                .hasCauseInstanceOf(DataIntegrityViolationException.class);

        assertThat(alwaysConflicts.saved)
                .as("nothing may be reported as persisted when every attempt was rejected")
                .isEmpty();
    }

    /** The ordinary case stays a single round trip: no retry when nothing conflicts. */
    @Test
    void anUncontendedAppendInsertsOnce() {
        RejectingTransitions noConflict = new RejectingTransitions(0);
        MeltSagaRepositoryAdapter adapter = adapterOver(noConflict);

        adapter.recordTransition(SAGA, MeltSagaState.PROOFS_HELD, MeltSagaState.FAILED, "reason", "sweep");

        assertThat(noConflict.attempts).isEqualTo(1);
        assertThat(noConflict.saved).hasSize(1);
    }

    /** The sequence is read per saga and incremented, so an append lands after the existing entries. */
    @Test
    void theAppendedSequenceFollowsTheCurrentMaximum() {
        RejectingTransitions transitions = new RejectingTransitions(0);
        transitions.maxSeq = 4;
        MeltSagaRepositoryAdapter adapter = adapterOver(transitions);

        adapter.recordTransition(SAGA, MeltSagaState.PROOFS_HELD, MeltSagaState.FAILED, "reason", "sweep");

        assertThat(transitions.saved.get(0).getSeq())
                .as("an append continues the saga's own timeline rather than restarting it")
                .isEqualTo(5);
    }

    /**
     * Wires the adapter to itself as its own proxy stand-in.
     *
     * <p>In production the {@code self} reference is the Spring proxy, so {@code REQUIRES_NEW} gives
     * each attempt its own transaction. There is no proxy here and no transaction to speak of, which is
     * exactly what makes this a unit test of the retry logic rather than of Spring's propagation.
     */
    private MeltSagaRepositoryAdapter adapterOver(RejectingTransitions transitions) {
        MeltSagaJpaRepository sagas = Mockito.mock(MeltSagaJpaRepository.class);
        // self is the adapter itself rather than a Spring proxy. In production the proxy is what makes
        // REQUIRES_NEW give each attempt its own transaction; here there is no transaction at all, which
        // is what keeps this a test of the retry logic rather than of Spring's propagation.
        MeltSagaRepositoryAdapter[] holder = new MeltSagaRepositoryAdapter[1];
        holder[0] = new MeltSagaRepositoryAdapter(sagas, transitions.repository, null);
        return new MeltSagaRepositoryAdapter(sagas, transitions.repository, holder[0]);
    }

    /**
     * A transition repository that rejects the first {@code rejections} inserts the way Postgres
     * rejects a duplicate primary key, then accepts.
     *
     * <p>Mockito rather than a hand-written class: {@link MeltSagaTransitionJpaRepository} inherits the
     * whole of {@code JpaRepository}, so implementing it directly would mean dozens of methods this
     * test never calls.
     */
    private static final class RejectingTransitions {

        private final MeltSagaTransitionJpaRepository repository =
                Mockito.mock(MeltSagaTransitionJpaRepository.class);
        private final List<MeltSagaTransitionEntity> saved = new ArrayList<>();
        private int attempts;
        private int maxSeq;

        private RejectingTransitions(int rejections) {
            Mockito.when(repository.maxSeq(Mockito.anyString())).thenAnswer(call -> maxSeq);
            Mockito.when(repository.saveAndFlush(Mockito.any(MeltSagaTransitionEntity.class)))
                    .thenAnswer(call -> {
                        attempts++;
                        if (attempts <= rejections) {
                            // Mirrors the real failure: another writer already took this
                            // (melt_saga_id, seq), and the timeline has therefore moved on.
                            maxSeq++;
                            throw new DataIntegrityViolationException(
                                    "duplicate key value violates unique constraint "
                                            + "\"melt_saga_transition_pk\"");
                        }
                        MeltSagaTransitionEntity entity = call.getArgument(0);
                        saved.add(entity);
                        return entity;
                    });
        }
    }
}
