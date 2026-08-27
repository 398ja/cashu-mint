package xyz.tcheeric.cashu.mint.admin.application.service;

import static java.util.Objects.requireNonNull;

import java.util.Set;

import xyz.tcheeric.cashu.mint.admin.application.port.in.AdministerAccessUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository.OperatorAccessAccount;

/**
 * Implements {@link AdministerAccessUseCase} for operator account management.
 */
public class AdministerAccessInteractor extends AbstractUseCaseInteractor
    implements AdministerAccessUseCase {

    private final OperatorAccessRepository operatorAccessRepository;

    public AdministerAccessInteractor(final OperatorAccessRepository operatorAccessRepository) {
        this.operatorAccessRepository =
            requireNonNull(operatorAccessRepository, "operator access repository must not be null");
    }

    @Override
    public AdministerAccessResponse handle(final AdministerAccessRequest request) {
        final AdministerAccessRequest validated = requireRequest(request, "administer access request");
        validateUuid(validated.operatorId(), "operator id");
        if (validated.command() == null) {
            throw new IllegalArgumentException("access command must not be null");
        }
        validateVersionTag(validated.versionTag());

        return switch (validated.command()) {
            case PROVISION -> provision(validated);
            case UPDATE_ROLES -> updateRoles(validated);
            case REVOKE -> revoke(validated);
        };
    }

    private AdministerAccessResponse provision(final AdministerAccessRequest request) {
        requireNonNull(request.targetAccountId(), "target account id must not be null");
        if (request.displayName() == null || request.displayName().isBlank()) {
            throw new IllegalArgumentException("display name must not be blank for provisioning");
        }
        if (request.roles() == null || request.roles().isEmpty()) {
            throw new IllegalArgumentException("at least one role must be provided for provisioning");
        }

        final OperatorAccessAccount record = new OperatorAccessAccount(
            request.targetAccountId(), request.displayName(), request.email(),
            Set.copyOf(request.roles()), true, request.pubkey());
        final boolean created = operatorAccessRepository.create(record);
        if (!created) {
            throw new IllegalStateException("operator already exists: " + request.targetAccountId());
        }

        return buildResponse(record, request.versionTag(), "User created");
    }

    private AdministerAccessResponse updateRoles(final AdministerAccessRequest request) {
        validateUuid(request.targetAccountId(), "target account id");
        if (request.roles() == null || request.roles().isEmpty()) {
            throw new IllegalArgumentException("at least one role must be provided");
        }

        final OperatorAccessAccount record = requireExisting(request.targetAccountId());
        final OperatorAccessAccount updated = new OperatorAccessAccount(
            record.accountId(),
            request.displayName() != null && !request.displayName().isBlank()
                ? request.displayName()
                : record.displayName(),
            request.email() != null ? request.email() : record.email(),
            Set.copyOf(request.roles()),
            record.active(),
            record.pubkey());
        operatorAccessRepository.update(updated);
        return buildResponse(updated, request.versionTag(), "User updated");
    }

    private AdministerAccessResponse revoke(final AdministerAccessRequest request) {
        validateUuid(request.targetAccountId(), "target account id");
        final OperatorAccessAccount record = requireExisting(request.targetAccountId());
        final OperatorAccessAccount updated = new OperatorAccessAccount(
            record.accountId(),
            record.displayName(),
            record.email(),
            record.roles(),
            false,
            record.pubkey());
        operatorAccessRepository.update(updated);
        return buildResponse(updated, request.versionTag(), "User deactivated");
    }

    private OperatorAccessAccount requireExisting(final String accountId) {
        return operatorAccessRepository.findById(accountId)
            .orElseThrow(() -> new IllegalStateException("operator not found: " + accountId));
    }

    private static AdministerAccessResponse buildResponse(final OperatorAccessAccount record,
                                                           final String versionTag,
                                                           final String message) {
        return new AdministerAccessResponse(record.accountId(), versionTag, record.displayName(),
            record.email(), record.roles(), record.active(), message);
    }
}
