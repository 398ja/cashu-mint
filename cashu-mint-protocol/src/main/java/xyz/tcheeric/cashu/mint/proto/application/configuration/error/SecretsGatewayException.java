package xyz.tcheeric.cashu.mint.proto.application.configuration.error;

/**
 * Wraps errors encountered when handling secrets for revisions.
 */
public class SecretsGatewayException extends ManageConfigurationException {

    public SecretsGatewayException(String message, Throwable cause) {
        super(ManageConfigurationErrorCode.SECRETS_GATEWAY_FAILURE, message, cause);
    }

    public SecretsGatewayException(String message) {
        super(ManageConfigurationErrorCode.SECRETS_GATEWAY_FAILURE, message);
    }
}
