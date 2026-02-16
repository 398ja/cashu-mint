package xyz.tcheeric.cashu.mint.admin.rest.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import xyz.tcheeric.cashu.mint.admin.application.port.in.AdministerAccessUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.in.AdministerAccessUseCase.AccessCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.in.AdministerAccessUseCase.AdministerAccessRequest;
import xyz.tcheeric.cashu.mint.admin.application.port.in.AdministerAccessUseCase.AdministerAccessResponse;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository.OperatorAccessAccount;
import xyz.tcheeric.cashu.mint.admin.rest.dto.common.PagedResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.AssignRolesRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.CreateUserRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.CredentialResetResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.ResetCredentialsRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.UpdateUserRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.UserLifecycleRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.UserResponse;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Delegates operator account workflows to {@link AdministerAccessUseCase}.
 */
@Service
public class AdminUserService {

    private static final String DEFAULT_VERSION_TAG = "v1";

    private final AdministerAccessUseCase accessUseCase;
    private final OperatorAccessRepository operatorRepository;

    public AdminUserService(final AdministerAccessUseCase accessUseCase,
                            final OperatorAccessRepository operatorRepository) {
        this.accessUseCase = Objects.requireNonNull(accessUseCase, "access use case must not be null");
        this.operatorRepository = Objects.requireNonNull(operatorRepository, "operator repository must not be null");
    }

    public PagedResponse<UserResponse> listUsers(final Boolean active, final String role,
                                                  final String q, final int page, final int size) {
        List<OperatorAccessAccount> accounts = operatorRepository.findAll();
        if (active != null) {
            accounts = accounts.stream().filter(a -> a.active() == active).toList();
        }
        if (role != null && !role.isBlank()) {
            final String upperRole = role.toUpperCase();
            accounts = accounts.stream().filter(a -> a.roles().contains(upperRole)).toList();
        }
        if (q != null && !q.isBlank()) {
            final String search = q.toLowerCase();
            accounts = accounts.stream()
                    .filter(a -> (a.displayName() != null && a.displayName().toLowerCase().contains(search))
                            || a.accountId().toLowerCase().contains(search))
                    .toList();
        }
        final List<UserResponse> items = accounts.stream()
                .map(a -> new UserResponse(a.accountId(), a.displayName(), a.email(),
                        a.roles(), a.active(), null))
                .toList();
        return PagedResponse.of(items, page, size);
    }

    public UserResponse getUser(final String userId) {
        final OperatorAccessAccount account = operatorRepository.findById(userId)
                .orElseThrow(() -> new AdminServiceException(
                        HttpStatus.NOT_FOUND, "user_not_found", "User not found: " + userId));
        return new UserResponse(account.accountId(), account.displayName(), account.email(),
                account.roles(), account.active(), null);
    }

    public UserResponse createUser(final CreateUserRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            final AdministerAccessResponse response = accessUseCase.handle(
                new AdministerAccessRequest(request.requestedBy().id(), request.userId(),
                    AccessCommand.PROVISION, DEFAULT_VERSION_TAG, request.displayName(),
                    request.email(), Set.copyOf(request.roles()), null));
            return toUserResponse(response);
        } catch (final IllegalStateException e) {
            throw mapDomainException(e);
        }
    }

    public UserResponse updateUser(final String userId, final UpdateUserRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            final AdministerAccessResponse response = accessUseCase.handle(
                new AdministerAccessRequest(request.requestedBy().id(), userId,
                    AccessCommand.UPDATE_ROLES, DEFAULT_VERSION_TAG, request.displayName(),
                    request.email(), Set.copyOf(request.roles()), null));
            return toUserResponse(response);
        } catch (final IllegalStateException e) {
            throw mapDomainException(e);
        }
    }

    public UserResponse assignRoles(final String userId, final AssignRolesRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            final AdministerAccessResponse response = accessUseCase.handle(
                new AdministerAccessRequest(request.requestedBy().id(), userId,
                    AccessCommand.UPDATE_ROLES, DEFAULT_VERSION_TAG, null,
                    null, Set.copyOf(request.roles()), request.justification()));
            return toUserResponse(response);
        } catch (final IllegalStateException e) {
            throw mapDomainException(e);
        }
    }

    public CredentialResetResponse resetCredentials(final String userId, final ResetCredentialsRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            final AdministerAccessResponse response = accessUseCase.handle(
                new AdministerAccessRequest(request.requestedBy().id(), userId,
                    AccessCommand.RESET_CREDENTIALS, DEFAULT_VERSION_TAG, null,
                    null, Set.of(), request.reason()));
            return new CredentialResetResponse(response.targetAccountId(),
                response.resetToken(), response.message());
        } catch (final IllegalStateException e) {
            throw mapDomainException(e);
        }
    }

    public UserResponse deactivateUser(final String userId, final UserLifecycleRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            final AdministerAccessResponse response = accessUseCase.handle(
                new AdministerAccessRequest(request.requestedBy().id(), userId,
                    AccessCommand.REVOKE, DEFAULT_VERSION_TAG, null,
                    null, Set.of(), request.reason()));
            return toUserResponse(response);
        } catch (final IllegalStateException e) {
            throw mapDomainException(e);
        }
    }

    private static UserResponse toUserResponse(final AdministerAccessResponse response) {
        return new UserResponse(response.targetAccountId(), response.displayName(),
            response.email(), response.roles(), response.active(), response.message());
    }

    private static AdminServiceException mapDomainException(final IllegalStateException e) {
        final String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (message.contains("not found")) {
            return new AdminServiceException(HttpStatus.NOT_FOUND, "user_not_found", e.getMessage());
        }
        if (message.contains("already exists")) {
            return new AdminServiceException(HttpStatus.CONFLICT, "user_exists", e.getMessage());
        }
        return new AdminServiceException(HttpStatus.INTERNAL_SERVER_ERROR, "access_error", e.getMessage());
    }
}
