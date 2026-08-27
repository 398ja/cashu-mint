package xyz.tcheeric.cashu.mint.admin.rest.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import xyz.tcheeric.cashu.mint.admin.application.port.in.AdministerAccessUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.in.AdministerAccessUseCase.AccessCommand;
import xyz.tcheeric.cashu.mint.admin.application.port.in.AdministerAccessUseCase.AdministerAccessRequest;
import xyz.tcheeric.cashu.mint.admin.application.port.in.AdministerAccessUseCase.AdministerAccessResponse;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository.OperatorAccessAccount;
import xyz.tcheeric.cashu.mint.admin.domain.AdminRole;
import xyz.tcheeric.cashu.mint.admin.rest.nap.AdminSecurityProperties;
import xyz.tcheeric.cashu.mint.admin.rest.nap.Npubs;
import xyz.tcheeric.cashu.mint.admin.rest.dto.common.PagedResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.AssignRolesRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.CreateUserRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.UpdateUserRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.UserLifecycleRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.UserResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Delegates operator account workflows to {@link AdministerAccessUseCase}.
 */
@Service
public class AdminUserService {

    private static final String DEFAULT_VERSION_TAG = "v1";

    private final AdministerAccessUseCase accessUseCase;
    private final OperatorAccessRepository operatorRepository;
    private final OperatorIdentity operatorIdentity;

    /** Lower-case hex of the configured Super Administrator, or null when none is configured. */
    private final String superAdminPubkey;

    public AdminUserService(final AdministerAccessUseCase accessUseCase,
                            final OperatorAccessRepository operatorRepository,
                            final OperatorIdentity operatorIdentity,
                            final AdminSecurityProperties securityProperties) {
        this.operatorIdentity = Objects.requireNonNull(operatorIdentity, "operator identity must not be null");
        this.accessUseCase = Objects.requireNonNull(accessUseCase, "access use case must not be null");
        this.operatorRepository = Objects.requireNonNull(operatorRepository, "operator repository must not be null");
        this.superAdminPubkey = decodeSuperAdmin(securityProperties);
    }

    // Blank is tolerated here rather than fatal: AdminNapConfiguration already refuses to
    // start NAP without it, and this service also runs in deployments with NAP disabled.
    private static String decodeSuperAdmin(final AdminSecurityProperties properties) {
        final String npub = properties == null ? null : properties.superAdminNpub();
        if (npub == null || npub.isBlank()) {
            return null;
        }
        return Npubs.toPubkeyHex(npub.strip()).toLowerCase();
    }

    public PagedResponse<UserResponse> listUsers(final Boolean active, final String role,
                                                  final String q, final int page, final int size) {
        List<OperatorAccessAccount> accounts = withSuperAdmin(operatorRepository.findAll());
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
        final List<UserResponse> items = accounts.stream().map(a -> toUserResponse(a, null)).toList();
        return PagedResponse.of(items, page, size);
    }

    /**
     * The Super Administrator is configuration rather than a stored profile, so a listing
     * built from the store alone omits the one account that outranks every entry in it.
     * When they do also hold a stored profile, that row stands in for them rather than
     * being listed twice.
     */
    private List<OperatorAccessAccount> withSuperAdmin(final List<OperatorAccessAccount> stored) {
        if (stored.stream().anyMatch(this::isSuperAdmin)) {
            return stored;
        }
        return superAdminAccount()
            .map(superAdmin -> {
                final List<OperatorAccessAccount> accounts = new ArrayList<>();
                accounts.add(superAdmin);
                accounts.addAll(stored);
                return List.copyOf(accounts);
            })
            .orElse(stored);
    }

    /** The configured Super Administrator as an account, or empty when none is configured. */
    private Optional<OperatorAccessAccount> superAdminAccount() {
        if (superAdminPubkey == null) {
            return Optional.empty();
        }
        return Optional.of(new OperatorAccessAccount(OperatorIdentity.derivedAccountId(superAdminPubkey),
            "Super Administrator", null, Set.of(AdminRole.SUPER_ADMIN.key()), true, superAdminPubkey));
    }

    private boolean isSuperAdmin(final OperatorAccessAccount account) {
        return superAdminPubkey != null && superAdminPubkey.equalsIgnoreCase(account.pubkey());
    }

    public UserResponse getUser(final String userId) {
        final OperatorAccessAccount account = operatorRepository.findById(userId)
                .or(() -> superAdminAccount().filter(a -> a.accountId().equals(userId)))
                .orElseThrow(() -> new AdminServiceException(
                        HttpStatus.NOT_FOUND, "user_not_found", "User not found: " + userId));
        return toUserResponse(account, null);
    }

    public UserResponse createUser(final CreateUserRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            final AdministerAccessResponse response = accessUseCase.handle(
                new AdministerAccessRequest(operatorIdentity.currentOperatorId(), request.userId(),
                    AccessCommand.PROVISION, DEFAULT_VERSION_TAG, request.displayName(),
                    request.email(), Set.copyOf(request.roles()),
                    Npubs.toPubkeyHex(request.npub()), null));
            return toUserResponse(response);
        } catch (final IllegalStateException e) {
            throw mapDomainException(e);
        }
    }

    public UserResponse updateUser(final String userId, final UpdateUserRequest request) {
        Objects.requireNonNull(request, "request");
        return administer(userId, new AdministerAccessRequest(operatorIdentity.currentOperatorId(), userId,
            AccessCommand.UPDATE_ROLES, DEFAULT_VERSION_TAG, request.displayName(),
            request.email(), Set.copyOf(request.roles()), null, null));
    }

    public UserResponse assignRoles(final String userId, final AssignRolesRequest request) {
        Objects.requireNonNull(request, "request");
        return administer(userId, new AdministerAccessRequest(operatorIdentity.currentOperatorId(), userId,
            AccessCommand.UPDATE_ROLES, DEFAULT_VERSION_TAG, null,
            null, Set.copyOf(request.roles()), null, request.justification()));
    }

    public UserResponse deactivateUser(final String userId, final UserLifecycleRequest request) {
        Objects.requireNonNull(request, "request");
        return administer(userId, new AdministerAccessRequest(operatorIdentity.currentOperatorId(), userId,
            AccessCommand.REVOKE, DEFAULT_VERSION_TAG, null,
            null, Set.of(), null, request.reason()));
    }

    public UserResponse reinstateUser(final String userId, final UserLifecycleRequest request) {
        Objects.requireNonNull(request, "request");
        return administer(userId, new AdministerAccessRequest(operatorIdentity.currentOperatorId(), userId,
            AccessCommand.REINSTATE, DEFAULT_VERSION_TAG, null,
            null, Set.of(), null, request.reason()));
    }

    private UserResponse administer(final String userId, final AdministerAccessRequest request) {
        requireNotSuperAdmin(userId);
        try {
            return toUserResponse(accessUseCase.handle(request));
        } catch (final IllegalStateException e) {
            throw mapDomainException(e);
        }
    }

    /**
     * The Super Administrator is the account that recovers the deployment, so no Operator
     * may suspend them or edit their roles — the entitlement comes from configuration and
     * a write here could only ever disagree with it.
     */
    private void requireNotSuperAdmin(final String userId) {
        final boolean anchored = superAdminAccount().filter(a -> a.accountId().equals(userId)).isPresent()
            || operatorRepository.findById(userId).filter(this::isSuperAdmin).isPresent();
        if (anchored) {
            throw new AdminServiceException(HttpStatus.FORBIDDEN, "super_admin_protected",
                "The Super Administrator is named in configuration and cannot be modified through this API");
        }
    }

    private UserResponse toUserResponse(final OperatorAccessAccount account, final String message) {
        return new UserResponse(account.accountId(), Npubs.toNpub(account.pubkey()),
            account.displayName(), account.email(),
            account.roles(), account.active(), message, isSuperAdmin(account));
    }

    private static UserResponse toUserResponse(final AdministerAccessResponse response) {
        // The use case answers about the change, not the profile: the npub travels on the
        // listing the caller reloads, so it is not restated here.
        return new UserResponse(response.targetAccountId(), null, response.displayName(),
            response.email(), response.roles(), response.active(), response.message(), false);
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
