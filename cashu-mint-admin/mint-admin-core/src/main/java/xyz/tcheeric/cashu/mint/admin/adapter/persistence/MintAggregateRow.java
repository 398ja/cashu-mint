package xyz.tcheeric.cashu.mint.admin.adapter.persistence;

import java.time.Instant;
import java.util.Objects;

/**
 * Relational representation of a {@link xyz.tcheeric.cashu.mint.admin.domain.MintAggregate}.
 *
 * <p>This record captures the minimal columns required for persistence while the
 * full mapping is being implemented.
 */
public record MintAggregateRow(
    String mintId,
    String lifecycleState,
    String configurationRevision,
    String operatorAccountId,
    String notificationPolicyReference,
    Instant auditTimestamp
) {

    public MintAggregateRow {
        Objects.requireNonNull(mintId, "mint identifier must not be null");
        Objects.requireNonNull(lifecycleState, "lifecycle state must not be null");
        Objects.requireNonNull(configurationRevision, "configuration revision must not be null");
        Objects.requireNonNull(operatorAccountId, "operator account identifier must not be null");
        Objects.requireNonNull(notificationPolicyReference, "notification policy reference must not be null");
        Objects.requireNonNull(auditTimestamp, "audit timestamp must not be null");
    }
}
