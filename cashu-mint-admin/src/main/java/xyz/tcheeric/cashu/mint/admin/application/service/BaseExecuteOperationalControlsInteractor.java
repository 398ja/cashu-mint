package xyz.tcheeric.cashu.mint.admin.application.service;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ExecuteOperationalControlsUseCase;

/**
 * Skeleton interactor that validates operational control requests prior to implementation.
 */
public class BaseExecuteOperationalControlsInteractor extends AbstractUseCaseInteractor
    implements ExecuteOperationalControlsUseCase {

    @Override
    public ExecuteOperationalControlsResponse handle(final ExecuteOperationalControlsRequest request) {
        final ExecuteOperationalControlsRequest validated = requireRequest(request, "execute operational controls request");
        validateMintId(validated.mintId());
        validateUuid(validated.operatorId(), "operator id");
        if (validated.command() == null) {
            throw new IllegalArgumentException("operational command must not be null");
        }
        validateVersionTag(validated.versionTag());
        throw new UnsupportedOperationException("ExecuteOperationalControlsUseCase has not been implemented yet");
    }
}
