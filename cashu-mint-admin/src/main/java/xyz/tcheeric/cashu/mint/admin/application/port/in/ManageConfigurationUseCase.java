package xyz.tcheeric.cashu.mint.admin.application.port.in;

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
                                      String versionTag) { }

    record ManageConfigurationResponse(String mintId,
                                       String appliedRevision,
                                       String versionTag) { }
}
