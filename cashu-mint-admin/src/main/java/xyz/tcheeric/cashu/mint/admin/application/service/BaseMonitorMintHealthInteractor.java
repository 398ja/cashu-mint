package xyz.tcheeric.cashu.mint.admin.application.service;

import xyz.tcheeric.cashu.mint.admin.application.port.in.MonitorMintHealthUseCase;

/**
 * Skeleton interactor that validates health monitoring requests prior to implementation.
 */
public class BaseMonitorMintHealthInteractor extends AbstractUseCaseInteractor implements MonitorMintHealthUseCase {

    @Override
    public MonitorMintHealthResponse handle(final MonitorMintHealthRequest request) {
        final MonitorMintHealthRequest validated = requireRequest(request, "monitor mint health request");
        validateMintId(validated.mintId());
        if (validated.query() == null) {
            throw new IllegalArgumentException("health query must not be null");
        }
        validateVersionTag(validated.versionTag());
        throw new UnsupportedOperationException("MonitorMintHealthUseCase has not been implemented yet");
    }
}
