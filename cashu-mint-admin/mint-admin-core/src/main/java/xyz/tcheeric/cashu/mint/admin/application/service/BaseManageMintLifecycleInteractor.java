package xyz.tcheeric.cashu.mint.admin.application.service;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageMintLifecycleUseCase;

/**
 * Skeleton interactor that validates input before lifecycle handling is implemented.
 */
public class BaseManageMintLifecycleInteractor extends AbstractUseCaseInteractor implements ManageMintLifecycleUseCase {

    @Override
    public ManageMintLifecycleResponse handle(final ManageMintLifecycleRequest request) {
        final ManageMintLifecycleRequest validated = requireRequest(request, "manage mint lifecycle request");
        validateMintId(validated.mintId());
        validateUuid(validated.operatorId(), "operator id");
        if (validated.command() == null) {
            throw new IllegalArgumentException("lifecycle command must not be null");
        }
        validateVersionTag(validated.versionTag());
        throw new UnsupportedOperationException("ManageMintLifecycleUseCase has not been implemented yet");
    }
}
