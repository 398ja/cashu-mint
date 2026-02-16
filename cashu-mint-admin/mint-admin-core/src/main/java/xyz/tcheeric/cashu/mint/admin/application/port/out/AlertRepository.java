package xyz.tcheeric.cashu.mint.admin.application.port.out;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Persistence port for alert lifecycle records.
 */
public interface AlertRepository {

    /**
     * Retrieve all alerts.
     *
     * @return list of all alert records
     */
    List<AlertRecord> findAll();

    /**
     * Persist a new alert.
     *
     * @param alert alert state to persist
     * @return true when the alert is created; false when it already exists
     */
    boolean create(AlertRecord alert);

    /**
     * Retrieve an alert by its identifier.
     *
     * @param alertId alert identifier
     * @return alert details when present
     */
    Optional<AlertRecord> findById(String alertId);

    /**
     * Persist updated alert state.
     *
     * @param alert alert state to persist
     */
    void update(AlertRecord alert);

    /**
     * Append an escalation policy reference for an alert.
     *
     * @param alertId alert identifier
     * @param policyId escalation policy identifier
     */
    void appendEscalation(String alertId, String policyId);

    /**
     * Immutable representation of a persisted alert.
     */
    record AlertRecord(String alertId,
                       String mintId,
                       String severity,
                       String summary,
                       Map<String, Object> labels,
                       boolean acknowledged,
                       boolean silenced,
                       Integer silenceMinutes,
                       List<String> escalations) {

        public AlertRecord {
            Objects.requireNonNull(alertId, "alert id must not be null");
            Objects.requireNonNull(mintId, "mint id must not be null");
            Objects.requireNonNull(severity, "severity must not be null");
            Objects.requireNonNull(summary, "summary must not be null");
            Objects.requireNonNull(labels, "labels must not be null");
            Objects.requireNonNull(escalations, "escalations must not be null");
            labels = Map.copyOf(labels);
            escalations = List.copyOf(escalations);
        }
    }
}
