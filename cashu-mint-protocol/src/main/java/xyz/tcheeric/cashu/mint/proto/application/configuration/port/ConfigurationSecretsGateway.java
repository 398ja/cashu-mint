package xyz.tcheeric.cashu.mint.proto.application.configuration.port;

import xyz.tcheeric.cashu.mint.proto.application.configuration.domain.ConfigurationRevision;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.SecretsBundle;
import xyz.tcheeric.cashu.mint.proto.application.configuration.dto.SecretsCommitResult;

/**
 * Provides access to secret material for configuration revisions.
 */
public interface ConfigurationSecretsGateway {

    SecretsBundle fetchSecrets(ConfigurationRevision revision, String requestedBy);

    SecretsCommitResult commitSecrets(ConfigurationRevision revision,
                                       SecretsBundle bundle,
                                       String requestedBy);
}
