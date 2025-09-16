package xyz.tcheeric.cashu.mint.admin.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * Immutable collection of audit events related to a mint aggregate.
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
public final class AuditTrail {

    private final List<AuditMetadata> entries;
    @Getter(AccessLevel.NONE)
    private final AuditMetadata auditMetadata;

    private AuditTrail(final List<AuditMetadata> entries, final AuditMetadata auditMetadata) {
        this.entries = List.copyOf(entries);
        this.auditMetadata = Objects.requireNonNull(auditMetadata, "audit metadata must not be null");
    }

    public static AuditTrail create(final AuditMetadata initialEntry) {
        Objects.requireNonNull(initialEntry, "initial audit entry must not be null");
        return new AuditTrail(List.of(initialEntry), initialEntry);
    }

    public AuditTrail append(final AuditMetadata entry) {
        Objects.requireNonNull(entry, "audit entry must not be null");
        final List<AuditMetadata> updated = new ArrayList<>(entries);
        updated.add(entry);
        return new AuditTrail(updated, entry);
    }

    public AuditMetadata latestMetadata() {
        return auditMetadata;
    }

    public List<String> latestReasonCodes() {
        return auditMetadata.reasonCodes();
    }

    public List<String> latestTicketReferences() {
        return auditMetadata.ticketReferences();
    }

    public AutomationContext latestAutomationContext() {
        return auditMetadata.automationContext();
    }

    public LifecycleContext latestLifecycleContext() {
        return auditMetadata.lifecycleContext();
    }
}
