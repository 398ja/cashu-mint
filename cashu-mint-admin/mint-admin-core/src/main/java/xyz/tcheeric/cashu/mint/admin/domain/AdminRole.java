package xyz.tcheeric.cashu.mint.admin.domain;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * The roles an Operator profile can hold.
 *
 * <p>Every key but {@code SUPER_ADMIN} is a string already persisted in operator
 * profiles and matched by the RBAC filter, so this enum names what was there
 * rather than introducing a second vocabulary. {@code SUPER_ADMIN} is new: it is
 * the configured Super Administrator's role, held by no stored profile.
 */
public enum AdminRole {

    /**
     * Manages Operators. Holds every permission because it is the account that
     * recovers a deployment whose other Operators are locked out.
     */
    SUPER_ADMIN("SUPER_ADMIN", Set.of(AdminPermission.values())),
    MINT_ADMIN("MINT_ADMIN", Set.of(AdminPermission.MINT_LIFECYCLE, AdminPermission.AUDIT_READ)),
    USER_ADMIN("USER_ADMIN", Set.of(AdminPermission.USERS_MANAGE)),
    OPS_ADMIN("OPS_ADMIN", Set.of(AdminPermission.OPERATIONS_EXECUTE));

    private final String key;
    private final Set<AdminPermission> permissions;

    AdminRole(final String key, final Set<AdminPermission> permissions) {
        this.key = key;
        this.permissions = Set.copyOf(permissions);
    }

    public String key() {
        return key;
    }

    public Set<AdminPermission> permissions() {
        return permissions;
    }

    /**
     * Resolve a persisted role name.
     *
     * @param key the stored role string
     * @return the matching role, or empty when the name is not one of ours —
     *     an unknown name grants nothing rather than falling back to a default
     */
    public static Optional<AdminRole> fromKey(final String key) {
        return Arrays.stream(values()).filter(role -> role.key.equals(key)).findFirst();
    }
}
