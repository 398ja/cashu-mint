package xyz.tcheeric.cashu.mint.proto.application.configuration.error;

/**
 * Thrown when a rollback request violates protection rules.
 */
public class RollbackProtectionException extends ManageConfigurationException {

    public RollbackProtectionException(String message) {
        super(ManageConfigurationErrorCode.ROLLBACK_NOT_ALLOWED, message);
    }
}
