package xyz.tcheeric.cashu.mint.proto.application.configuration.error;

/**
 * Raised when an operation requires approvals that are not yet satisfied.
 */
public class MissingApprovalException extends ManageConfigurationException {

    public MissingApprovalException(String message) {
        super(ManageConfigurationErrorCode.MISSING_APPROVAL, message);
    }
}
