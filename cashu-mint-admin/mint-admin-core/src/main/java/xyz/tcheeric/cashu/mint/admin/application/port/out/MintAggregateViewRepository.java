package xyz.tcheeric.cashu.mint.admin.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.LifecycleState;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * Persistence port exposing read-model snapshots for mint aggregates.
 */
public interface MintAggregateViewRepository {

    /**
     * Persist or update the aggregate snapshot derived from the supplied event.
     *
     * @param event the lifecycle event to project
     */
    void upsert(MintLifecycleEvent event);

    /**
     * Retrieve the latest aggregate snapshot for the supplied identifier.
     *
     * @param mintId the aggregate identifier
     * @return the snapshot if present
     */
    Optional<MintAggregateView> findById(MintId mintId);

    /**
     * Load all known aggregate snapshots.
     *
     * @return immutable list of aggregate snapshots
     */
    List<MintAggregateView> findAll();

    /**
     * Projection representing the persisted state of a mint aggregate.
     */
    record MintAggregateView(MintId mintId,
                             LifecycleState.State lifecycleState,
                             ConfigurationRevisionId configurationRevisionId,
                             String versionTag,
                             Instant updatedAt) {

        public MintAggregateView {
            Objects.requireNonNull(mintId, "mint identifier must not be null");
            Objects.requireNonNull(lifecycleState, "lifecycle state must not be null");
            Objects.requireNonNull(configurationRevisionId, "configuration revision must not be null");
            Objects.requireNonNull(updatedAt, "last update timestamp must not be null");
        }
    }
}
