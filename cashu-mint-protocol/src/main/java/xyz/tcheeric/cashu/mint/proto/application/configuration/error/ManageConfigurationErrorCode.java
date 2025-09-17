package xyz.tcheeric.cashu.mint.proto.application.configuration.error;

/**
 * Error codes for configuration management workflows.
 */
public enum ManageConfigurationErrorCode {
    VALIDATION_FAILED,
    REVISION_NOT_FOUND,
    MISSING_APPROVAL,
    ROLLBACK_NOT_ALLOWED,
    SECRETS_GATEWAY_FAILURE,
    PERSISTENCE_FAILURE,
    APPROVAL_POLICY_FAILURE
}
