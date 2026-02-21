package xyz.tcheeric.cashu.mint.admin.application.service;

import xyz.tcheeric.cashu.mint.admin.application.port.in.AdministerAccessUseCase;

/**
 * Skeleton interactor that validates access administration requests before behaviour is implemented.
 */
public class BaseAdministerAccessInteractor extends AbstractUseCaseInteractor implements AdministerAccessUseCase {

    @Override
    public AdministerAccessResponse handle(final AdministerAccessRequest request) {
        final AdministerAccessRequest validated = requireRequest(request, "administer access request");
        validateUuid(validated.operatorId(), "operator id");
        validateUuid(validated.targetAccountId(), "target account id");
        if (validated.command() == null) {
            throw new IllegalArgumentException("access command must not be null");
        }
        validateVersionTag(validated.versionTag());
        throw new UnsupportedOperationException("AdministerAccessUseCase has not been implemented yet");
    }
}
