package xyz.tcheeric.cashu.mint.admin.application.port.out;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase.ConfigurationValueInput;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationRevisionId;
import xyz.tcheeric.cashu.mint.admin.domain.ConfigurationValue;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * Handles secret material used in configuration revisions.
 */
public interface ConfigurationSecretManager {

    /**
     * Convert the supplied value input into a persisted configuration value, handling secret creation when required.
     *
     * @param mintId the mint identifier
     * @param revisionId the configuration revision identifier
     * @param parameterKey the parameter key being updated
     * @param input the user supplied value input
     * @return configuration value suitable for persistence
     */
    ConfigurationValue prepareValue(MintId mintId,
                                    ConfigurationRevisionId revisionId,
                                    String parameterKey,
                                    ConfigurationValueInput input);
}
