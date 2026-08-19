package xyz.tcheeric.cashu.mint.admin.application.service;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
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
            case RESET_CREDENTIALS -> resetCredentials(validated);
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

        // Issued here so a new operator is usable from one call. The bootstrap
        // credential goes inert as soon as an operator exists, and would otherwise
        // be unable to hand the first operator its own way in.
        final String credential = OperatorCredentials.issue();
        final OperatorAccessAccount record = new OperatorAccessAccount(
            request.targetAccountId(), request.displayName(), request.email(),
            Set.copyOf(request.roles()), true, 1, OperatorCredentials.hash(credential), Instant.now());
        final boolean created = operatorAccessRepository.create(record);
        if (!created) {
            throw new IllegalStateException("operator already exists: " + request.targetAccountId());
        }

        return buildResponse(record, request.versionTag(), "User created", credential);
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
            record.credentialResetCount(),
            record.credentialHash(),
            record.lastResetAt());
        operatorAccessRepository.update(updated);
        return buildResponse(updated, request.versionTag(), "User updated", null);
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
            record.credentialResetCount(),
            record.credentialHash(),
            record.lastResetAt());
        operatorAccessRepository.update(updated);
        return buildResponse(updated, request.versionTag(), "User deactivated", null);
    }

    private AdministerAccessResponse resetCredentials(final AdministerAccessRequest request) {
        validateUuid(request.targetAccountId(), "target account id");
        final OperatorAccessAccount record = requireExisting(request.targetAccountId());
        final int resetCount = record.credentialResetCount() + 1;
        // The plaintext credential is returned to the caller once and never stored.
        final String credential = OperatorCredentials.issue();
        final OperatorAccessAccount updated = new OperatorAccessAccount(
            record.accountId(),
            record.displayName(),
            record.email(),
            record.roles(),
            record.active(),
            resetCount,
            OperatorCredentials.hash(credential),
            Instant.now());
        operatorAccessRepository.update(updated);
        return buildResponse(updated, request.versionTag(), "Credential issued", credential);
    }

    private OperatorAccessAccount requireExisting(final String accountId) {
        return operatorAccessRepository.findById(accountId)
            .orElseThrow(() -> new IllegalStateException("operator not found: " + accountId));
    }

    private static AdministerAccessResponse buildResponse(final OperatorAccessAccount record,
                                                           final String versionTag,
                                                           final String message,
                                                           final String resetToken) {
        return new AdministerAccessResponse(record.accountId(), versionTag, record.displayName(),
            record.email(), record.roles(), record.active(), message, resetToken);
    }
}
