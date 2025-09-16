package xyz.tcheeric.cashu.mint.admin.rest.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import xyz.tcheeric.cashu.mint.admin.rest.dto.users.AssignRolesRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.CreateUserRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.CredentialResetResponse;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.ResetCredentialsRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.UpdateUserRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.UserLifecycleRequest;
import xyz.tcheeric.cashu.mint.admin.rest.dto.users.UserResponse;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory operator account service mirroring CLI workflows for the REST API.
 */
@Service
public class AdminUserService {

    private final ConcurrentMap<String, UserRecord> users = new ConcurrentHashMap<>();
    private final AtomicInteger resetSequence = new AtomicInteger(1);

    public UserResponse createUser(final CreateUserRequest request) {
        Objects.requireNonNull(request, "request");
        final UserRecord newUser = new UserRecord(request.userId(), request.displayName(), request.email(),
                Set.copyOf(request.roles()), true);
        final UserRecord existing = users.putIfAbsent(newUser.userId, newUser);
        if (existing != null) {
            throw new AdminServiceException(HttpStatus.CONFLICT, "user_exists", "User already exists: " + request.userId());
        }
        return toResponse(newUser, "User created");
    }

    public UserResponse updateUser(final String userId, final UpdateUserRequest request) {
        Objects.requireNonNull(request, "request");
        final UserRecord updated = users.computeIfPresent(userId, (id, current) -> {
            current.displayName = request.displayName();
            current.email = request.email();
            current.roles = Set.copyOf(request.roles());
            return current;
        });
        if (updated == null) {
            throw new AdminServiceException(HttpStatus.NOT_FOUND, "user_not_found", "User not found: " + userId);
        }
        return toResponse(updated, "User updated");
    }

    public UserResponse assignRoles(final String userId, final AssignRolesRequest request) {
        Objects.requireNonNull(request, "request");
        final UserRecord updated = users.computeIfPresent(userId, (id, current) -> {
            current.roles = Set.copyOf(request.roles());
            return current;
        });
        if (updated == null) {
            throw new AdminServiceException(HttpStatus.NOT_FOUND, "user_not_found", "User not found: " + userId);
        }
        return toResponse(updated, "Roles updated");
    }

    public CredentialResetResponse resetCredentials(final String userId, final ResetCredentialsRequest request) {
        Objects.requireNonNull(request, "request");
        final UserRecord user = users.get(userId);
        if (user == null) {
            throw new AdminServiceException(HttpStatus.NOT_FOUND, "user_not_found", "User not found: " + userId);
        }
        final String token = userId + "-reset-" + resetSequence.getAndIncrement();
        user.lastResetToken = token;
        return new CredentialResetResponse(userId, token, "Reset token issued");
    }

    public UserResponse deactivateUser(final String userId, final UserLifecycleRequest request) {
        Objects.requireNonNull(request, "request");
        final UserRecord updated = users.computeIfPresent(userId, (id, current) -> {
            current.active = false;
            return current;
        });
        if (updated == null) {
            throw new AdminServiceException(HttpStatus.NOT_FOUND, "user_not_found", "User not found: " + userId);
        }
        return toResponse(updated, "User deactivated");
    }

    private static UserResponse toResponse(final UserRecord record, final String message) {
        return new UserResponse(record.userId, record.displayName, record.email, record.roles, record.active, message);
    }

    private static final class UserRecord {
        private final String userId;
        private String displayName;
        private String email;
        private Set<String> roles;
        private boolean active;
        private String lastResetToken;

        private UserRecord(final String userId,
                           final String displayName,
                           final String email,
                           final Set<String> roles,
                           final boolean active) {
            this.userId = Objects.requireNonNull(userId, "userId");
            this.displayName = displayName;
            this.email = email;
            this.roles = roles;
            this.active = active;
        }
    }
}
