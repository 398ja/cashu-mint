package xyz.tcheeric.cashu.mint.admin.domain;

/**
 * Lifecycle states for a configuration revision.
 */
public enum ConfigurationRevisionState {
    DRAFT,
    VALIDATED,
    APPROVED,
    APPLIED,
    ROLLED_BACK,
    REJECTED
}
