package xyz.tcheeric.cashu.mint.admin.application.port.out;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * Persistence port for storing and querying the lifecycle history of mint aggregates.
 */
public interface MintLifecycleHistoryRepository {

    /**
     * Append a lifecycle event to the persisted history stream.
     *
     * @param eventId correlation identifier shared with the outbox
     * @param event the lifecycle event to record
     */
    void append(UUID eventId, MintLifecycleEvent event);

    /**
     * Retrieve the recorded lifecycle history for the supplied mint.
     *
     * @param mintId the aggregate identifier
     * @return immutable list of history entries ordered by occurrence
     */
    List<MintLifecycleHistoryEntry> findByMintId(MintId mintId);

    /**
     * Immutable representation of a persisted lifecycle event.
     */
    record MintLifecycleHistoryEntry(UUID eventId, MintLifecycleEvent event) {

        public MintLifecycleHistoryEntry {
            Objects.requireNonNull(eventId, "event id must not be null");
            Objects.requireNonNull(event, "lifecycle event must not be null");
        }
    }
}
