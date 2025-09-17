package xyz.tcheeric.cashu.mint.proto.application.configuration.error;

/**
 * Indicates that a revision failed schema validation.
 */
public class ValidationFailedException extends ManageConfigurationException {

    public ValidationFailedException(String message) {
        super(ManageConfigurationErrorCode.VALIDATION_FAILED, message);
    }
}
