package xyz.tcheeric.cashu.mint.admin.domain;

import java.util.Objects;
import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Event emitted when a configuration revision receives approval.
 */
@Value
@Accessors(fluent = true)
public final class ConfigurationApprovedEvent implements ConfigurationLifecycleEvent {

    ConfigurationRevisionId revisionId;
    AuditMetadata metadata;
    ApprovalRecord approvalRecord;

    public ConfigurationApprovedEvent(final ConfigurationRevisionId revisionId,
                                      final AuditMetadata metadata,
                                      final ApprovalRecord approvalRecord) {
        this.revisionId = Objects.requireNonNull(revisionId, "revision id must not be null");
        this.metadata = Objects.requireNonNull(metadata, "metadata must not be null");
        this.approvalRecord = Objects.requireNonNull(approvalRecord, "approval record must not be null");
    }

    @Override
    public ConfigurationRevisionState state() {
        return ConfigurationRevisionState.APPROVED;
    }
}
