package xyz.tcheeric.cashu.mint.admin.domain;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import lombok.Value;
import lombok.experimental.Accessors;

/**
 * Operator responsible for managing a mint.
 */
@Value
@Accessors(fluent = true)
public class OperatorAccount {

    UUID operatorId;
    String displayName;
    Set<String> roles;
    AuditMetadata auditMetadata;

    public OperatorAccount(final UUID operatorId,
                           final String displayName,
                           final Set<String> roles,
                           final AuditMetadata auditMetadata) {
        this.operatorId = Objects.requireNonNull(operatorId, "operator id must not be null");
        this.displayName = requireNonBlank(displayName, "display name");
        this.roles = Set.copyOf(validateRoles(roles));
        this.auditMetadata = Objects.requireNonNull(auditMetadata, "audit metadata must not be null");
    }

    private static Set<String> validateRoles(final Set<String> roles) {
        Objects.requireNonNull(roles, "roles must not be null");
        for (final String role : roles) {
            requireNonBlank(role, "role");
        }
        return roles;
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    public OperatorAccount rename(final String newDisplayName, final AuditMetadata metadata) {
        return new OperatorAccount(operatorId, requireNonBlank(newDisplayName, "display name"), roles,
            Objects.requireNonNull(metadata, "audit metadata must not be null"));
    }

    public OperatorAccount updateRoles(final Set<String> newRoles, final AuditMetadata metadata) {
        return new OperatorAccount(operatorId, displayName, Set.copyOf(validateRoles(newRoles)),
            Objects.requireNonNull(metadata, "audit metadata must not be null"));
    }
}
