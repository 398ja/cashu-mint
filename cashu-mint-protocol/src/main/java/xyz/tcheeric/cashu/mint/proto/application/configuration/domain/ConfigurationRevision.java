package xyz.tcheeric.cashu.mint.proto.application.configuration.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ApprovalStateSnapshot;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.DiffArtifact;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.ValidationSummary;

/**
 * Aggregate representing a configuration revision.
 */
public record ConfigurationRevision(
        UUID revisionId,
        String scope,
        UUID baseRevisionId,
        RevisionStatus status,
        boolean includesSecrets,
        DiffArtifact diffArtifact,
        ValidationSummary validationSummary,
        ApprovalStateSnapshot approvalState,
        Optional<Instant> appliedAt,
        Instant createdAt,
        Instant updatedAt
) {

    public ConfigurationRevision {
        Objects.requireNonNull(revisionId, "revisionId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(diffArtifact, "diffArtifact");
        Objects.requireNonNull(validationSummary, "validationSummary");
        Objects.requireNonNull(approvalState, "approvalState");
        Objects.requireNonNull(appliedAt, "appliedAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public boolean isApproved() {
        return status == RevisionStatus.APPROVED || status == RevisionStatus.APPLIED;
    }

    public boolean isApplied() {
        return status == RevisionStatus.APPLIED;
    }

    public boolean canApply() {
        return isApproved() && appliedAt.isEmpty();
    }

    public boolean canRollback() {
        return isApplied();
    }

    public ConfigurationRevision withStatus(RevisionStatus newStatus) {
        return new ConfigurationRevision(
                revisionId,
                scope,
                baseRevisionId,
                newStatus,
                includesSecrets,
                diffArtifact,
                validationSummary,
                approvalState,
                appliedAt,
                createdAt,
                Instant.now());
    }

    public ConfigurationRevision withApprovalState(ApprovalStateSnapshot snapshot) {
        return new ConfigurationRevision(
                revisionId,
                scope,
                baseRevisionId,
                status,
                includesSecrets,
                diffArtifact,
                validationSummary,
                snapshot,
                appliedAt,
                createdAt,
                Instant.now());
    }

    public ConfigurationRevision withValidationSummary(ValidationSummary summary) {
        return new ConfigurationRevision(
                revisionId,
                scope,
                baseRevisionId,
                status,
                includesSecrets,
                diffArtifact,
                summary,
                approvalState,
                appliedAt,
                createdAt,
                Instant.now());
    }

    public ConfigurationRevision withDiffArtifact(DiffArtifact diff) {
        return new ConfigurationRevision(
                revisionId,
                scope,
                baseRevisionId,
                status,
                includesSecrets,
                diff,
                validationSummary,
                approvalState,
                appliedAt,
                createdAt,
                Instant.now());
    }

    public ConfigurationRevision withAppliedAt(Instant when) {
        return new ConfigurationRevision(
                revisionId,
                scope,
                baseRevisionId,
                RevisionStatus.APPLIED,
                includesSecrets,
                diffArtifact,
                validationSummary,
                approvalState,
                Optional.ofNullable(when),
                createdAt,
                Instant.now());
    }
}
