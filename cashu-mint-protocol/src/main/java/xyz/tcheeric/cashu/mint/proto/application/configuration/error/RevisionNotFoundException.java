package xyz.tcheeric.cashu.mint.proto.application.configuration.error;

/**
 * Thrown when a revision cannot be located.
 */
public class RevisionNotFoundException extends ManageConfigurationException {

    public RevisionNotFoundException(String message) {
        super(ManageConfigurationErrorCode.REVISION_NOT_FOUND, message);
    }
}
