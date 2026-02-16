package xyz.tcheeric.cashu.mint.admin.application.port.out;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * Persistence port for mint health snapshots.
 */
public interface MintHealthRepository {

    /**
     * Persist or update a mint health snapshot.
     *
     * @param snapshot snapshot to persist
     */
    void upsert(MintHealthSnapshot snapshot);

    /**
     * Retrieve the latest health snapshot for the supplied mint.
     *
     * @param mintId mint identifier
     * @return health snapshot when present
     */
    Optional<MintHealthSnapshot> findByMintId(MintId mintId);

    /**
     * Persisted health levels.
     */
    enum MintHealthStatus {
        HEALTHY,
        WARNING,
        CRITICAL,
        UNKNOWN
    }

    /**
     * Immutable representation of a persisted health snapshot.
     */
    record MintHealthSnapshot(MintId mintId,
                              MintHealthStatus status,
                              String lifecycleState,
                              Instant checkedAt) {

        public MintHealthSnapshot {
            Objects.requireNonNull(mintId, "mint id must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(checkedAt, "checked-at timestamp must not be null");
        }
    }
}
