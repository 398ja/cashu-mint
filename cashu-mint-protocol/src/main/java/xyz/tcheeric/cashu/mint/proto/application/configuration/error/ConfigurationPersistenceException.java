package xyz.tcheeric.cashu.mint.proto.application.configuration.error;

/**
 * Signals an unrecoverable persistence failure.
 */
public class ConfigurationPersistenceException extends ManageConfigurationException {

    public ConfigurationPersistenceException(String message, Throwable cause) {
        super(ManageConfigurationErrorCode.PERSISTENCE_FAILURE, message, cause);
    }

    public ConfigurationPersistenceException(String message) {
        super(ManageConfigurationErrorCode.PERSISTENCE_FAILURE, message);
    }
}
