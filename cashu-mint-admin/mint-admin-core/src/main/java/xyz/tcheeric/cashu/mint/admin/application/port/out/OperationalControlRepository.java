package xyz.tcheeric.cashu.mint.admin.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * Persistence port for operational control execution records.
 */
public interface OperationalControlRepository {

    /**
     * Persist a new operational control record.
     *
     * @param control control record to persist
     */
    void create(OperationalControlRecord control);

    /**
     * Persist updated operational control state.
     *
     * @param control control record to update
     */
    void update(OperationalControlRecord control);

    /**
     * Retrieve the active maintenance control for a mint.
     *
     * @param mintId mint identifier
     * @return active maintenance record when present
     */
    Optional<OperationalControlRecord> findActiveMaintenanceByMintId(MintId mintId);

    /**
     * Retrieve all operational controls for a mint.
     *
     * @param mintId mint identifier
     * @return list of control records ordered by scheduled time descending
     */
    List<OperationalControlRecord> findByMintId(MintId mintId);

    /**
     * Retrieve a single operational control by its identifier.
     *
     * @param controlId control identifier
     * @return the control when present
     */
    Optional<OperationalControlRecord> findById(String controlId);

    /**
     * Persisted operational control categories.
     */
    enum OperationalControlType {
        MAINTENANCE,
        KEY_ROTATION,
        FORCE_CLOSE
    }

    /**
     * Immutable representation of a persisted operational control action.
     */
    record OperationalControlRecord(String controlId,
                                    MintId mintId,
                                    UUID operatorId,
                                    OperationalControlType controlType,
                                    String status,
                                    Instant scheduledAt,
                                    String reason,
                                    Integer durationMinutes,
                                    String outcome) {

        /** A control that has not completed yet carries no outcome. */
        public OperationalControlRecord(final String controlId,
                                        final MintId mintId,
                                        final UUID operatorId,
                                        final OperationalControlType controlType,
                                        final String status,
                                        final Instant scheduledAt,
                                        final String reason,
                                        final Integer durationMinutes) {
            this(controlId, mintId, operatorId, controlType, status, scheduledAt, reason, durationMinutes, null);
        }

        public OperationalControlRecord {
            Objects.requireNonNull(controlId, "control id must not be null");
            Objects.requireNonNull(mintId, "mint id must not be null");
            Objects.requireNonNull(operatorId, "operator id must not be null");
            Objects.requireNonNull(controlType, "control type must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(scheduledAt, "scheduled-at timestamp must not be null");
        }
    }
}
