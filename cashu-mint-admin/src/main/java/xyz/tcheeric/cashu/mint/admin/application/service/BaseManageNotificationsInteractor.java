package xyz.tcheeric.cashu.mint.admin.application.service;

import xyz.tcheeric.cashu.mint.admin.application.port.in.ManageNotificationsUseCase;

/**
 * Skeleton interactor that validates notification management requests prior to implementation.
 */
public class BaseManageNotificationsInteractor extends AbstractUseCaseInteractor implements ManageNotificationsUseCase {

    @Override
    public ManageNotificationsResponse handle(final ManageNotificationsRequest request) {
        final ManageNotificationsRequest validated = requireRequest(request, "manage notifications request");
        validateMintId(validated.mintId());
        validateUuid(validated.policyId(), "policy id");
        if (validated.command() == null) {
            throw new IllegalArgumentException("notification command must not be null");
        }
        validateVersionTag(validated.versionTag());
        throw new UnsupportedOperationException("ManageNotificationsUseCase has not been implemented yet");
    }
}
