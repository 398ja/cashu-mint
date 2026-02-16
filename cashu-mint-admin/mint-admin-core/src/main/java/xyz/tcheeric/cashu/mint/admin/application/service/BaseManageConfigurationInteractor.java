package xyz.tcheeric.cashu.mint.admin.application.service;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageConfigurationUseCase;

/**
 * Skeleton interactor that validates configuration requests before functionality is delivered.
 */
public class BaseManageConfigurationInteractor extends AbstractUseCaseInteractor implements ManageConfigurationUseCase {

    @Override
    public ManageConfigurationResponse handle(final ManageConfigurationRequest request) {
        final ManageConfigurationRequest validated = requireRequest(request, "manage configuration request");
        validateMintId(validated.mintId());
        validateUuid(validated.operatorId(), "operator id");
        validateConfigurationRevision(validated.targetRevision());
        if (validated.command() == null) {
            throw new IllegalArgumentException("configuration command must not be null");
        }
        validateVersionTag(validated.versionTag());
        throw new UnsupportedOperationException("ManageConfigurationUseCase has not been implemented yet");
    }
}
