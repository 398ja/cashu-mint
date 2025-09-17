package xyz.tcheeric.cashu.mint.proto.application.configuration.domain;

/**
 * Lifecycle states for configuration revisions.
 */
public enum RevisionStatus {
    DRAFT,
    VALIDATED,
    APPROVAL_PENDING,
    APPROVED,
    REJECTED,
    APPLIED,
    ROLLED_BACK
}
