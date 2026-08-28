package xyz.tcheeric.cashu.mint.admin.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Append-only record of who changed operator access, and to whom.
 *
 * <p>Separate from {@code audit_events}: that trail is keyed by mint, and access
 * management is not about a mint. Entries are never updated or deleted — a suspended
 * Operator stays resolvable from the trail long after they stop being able to sign in.
 */
public interface OperatorAccessAuditRepository {

    /**
     * Append one entry.
     *
     * @param entry the action to record
     */
    void record(OperatorAccessAuditEntry entry);

    /**
     * Read the whole trail, newest first.
     *
     * @return every recorded access-management action
     */
    List<OperatorAccessAuditEntry> findAll();

    /**
     * One management action, naming the Operator who took it.
     */
    record OperatorAccessAuditEntry(String actor, String action, String targetAccountId, Instant occurredAt) {

        public OperatorAccessAuditEntry {
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(action, "action must not be null");
            Objects.requireNonNull(targetAccountId, "target account id must not be null");
            Objects.requireNonNull(occurredAt, "occurred at must not be null");
        }
    }
}
