package xyz.tcheeric.cashu.mint.proto.application.configuration.error;

/**
 * Base exception for configuration management errors.
 */
public class ManageConfigurationException extends RuntimeException {

    private final ManageConfigurationErrorCode errorCode;

    public ManageConfigurationException(ManageConfigurationErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ManageConfigurationException(ManageConfigurationErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ManageConfigurationErrorCode getErrorCode() {
        return errorCode;
    }
}
