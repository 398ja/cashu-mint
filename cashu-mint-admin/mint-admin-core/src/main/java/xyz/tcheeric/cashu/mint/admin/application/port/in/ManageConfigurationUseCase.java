package xyz.tcheeric.cashu.mint.admin.application.port.in;

import java.util.Map;

/**
 * Handles configuration workflows such as versioning, diffing, and rollback.
 */
public interface ManageConfigurationUseCase {

    ManageConfigurationResponse handle(ManageConfigurationRequest request);

    enum ConfigurationCommand {
        APPLY,
        ROLLBACK,
        VALIDATE,
        DIFF
    }

    record ManageConfigurationRequest(String mintId,
                                      String operatorId,
                                      String targetRevision,
                                      ConfigurationCommand command,
                                      String versionTag,
                                      Map<String, String> parameters) {

        public ManageConfigurationRequest(String mintId,
                                          String operatorId,
                                          String targetRevision,
                                          ConfigurationCommand command,
                                          String versionTag) {
            this(mintId, operatorId, targetRevision, command, versionTag, Map.of());
        }
    }

    record ManageConfigurationResponse(String mintId,
                                       String appliedRevision,
                                       String versionTag,
                                       Map<String, String> parameters,
                                       String message) { }
}
