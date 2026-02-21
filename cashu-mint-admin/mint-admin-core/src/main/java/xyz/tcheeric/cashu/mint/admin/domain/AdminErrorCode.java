package xyz.tcheeric.cashu.mint.admin.domain;

/**
 * Machine-readable error codes shared across CLI and REST layers.
 */
public enum AdminErrorCode {

    MINT_NOT_FOUND("mint_not_found", "Mint not found"),
    MINT_ALREADY_EXISTS("mint_already_exists", "Mint already exists"),
    INVALID_TRANSITION("invalid_transition", "Invalid lifecycle state transition"),
    APPROVAL_REQUIRED("approval_required", "Approval is required for this operation"),
    CONFIGURATION_CONFLICT("configuration_conflict", "Configuration revision conflict"),
    CONFIGURATION_NOT_FOUND("configuration_not_found", "Configuration revision not found"),
    USER_NOT_FOUND("user_not_found", "User not found"),
    USER_ALREADY_EXISTS("user_exists", "User already exists"),
    ALERT_NOT_FOUND("alert_not_found", "Alert not found"),
    ALERT_ALREADY_EXISTS("alert_exists", "Alert already exists"),
    MAINTENANCE_NOT_FOUND("maintenance_not_found", "No active maintenance window found"),
    UNAUTHORIZED("unauthorized", "Authentication required"),
    FORBIDDEN("forbidden", "Insufficient permissions"),
    INVALID_REQUEST("invalid_request", "Invalid request"),
    HEALTH_ERROR("health_error", "Health monitoring error"),
    OPERATIONS_ERROR("operations_error", "Operational control error"),
    INTERNAL_ERROR("internal_error", "Internal server error");

    private final String code;
    private final String defaultMessage;

    AdminErrorCode(final String code, final String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
