package xyz.tcheeric.cashu.mint.proto.application.configuration.error;

/**
 * Indicates that the approval policy engine could not process a request.
 */
public class ApprovalPolicyException extends ManageConfigurationException {

    public ApprovalPolicyException(String message, Throwable cause) {
        super(ManageConfigurationErrorCode.APPROVAL_POLICY_FAILURE, message, cause);
    }

    public ApprovalPolicyException(String message) {
        super(ManageConfigurationErrorCode.APPROVAL_POLICY_FAILURE, message);
    }
}
