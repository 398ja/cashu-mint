package xyz.tcheeric.cashu.mint.admin.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AdminRoleTest {

    // The role names the RBAC filter and the operator store already exchange as
    // strings have to survive being declared as an enum, or every stored role
    // silently stops resolving.
    @Test
    @DisplayName("Role keys match the names already persisted in operator profiles")
    void roleKeysMatchPersistedNames() {
        final Set<String> keys = Arrays.stream(AdminRole.values())
            .map(AdminRole::key)
            .collect(Collectors.toSet());

        assertThat(keys).containsExactlyInAnyOrder(
            "SUPER_ADMIN", "MINT_ADMIN", "USER_ADMIN", "OPS_ADMIN");
    }

    // The Super Administrator is the one role that manages Operators, and it has
    // to hold everything the other roles hold — it is the account that recovers a
    // deployment whose other Operators are locked out.
    @Test
    @DisplayName("Super Administrator holds every declared permission")
    void superAdminHoldsEveryPermission() {
        assertThat(AdminRole.SUPER_ADMIN.permissions())
            .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(AdminPermission.class));
    }

    // Only the Super Administrator may manage Operators; a role that acquired it
    // by accident would let an Operator grant themselves anything.
    @Test
    @DisplayName("Operator management belongs to the Super Administrator alone")
    void operatorManagementIsSuperAdminOnly() {
        final Set<AdminRole> holders = Arrays.stream(AdminRole.values())
            .filter(role -> role.permissions().contains(AdminPermission.OPERATORS_MANAGE))
            .collect(Collectors.toSet());

        assertThat(holders).containsExactly(AdminRole.SUPER_ADMIN);
    }

    // The dashboard's recent-activity panel is built from the audit trail, so a role
    // without audit:read lands on a first screen that reports forbidden.
    @Test
    @DisplayName("Every role reads the audit trail")
    void everyRoleReadsTheAuditTrail() {
        assertThat(Arrays.stream(AdminRole.values())
            .filter(role -> !role.permissions().contains(AdminPermission.AUDIT_READ))
            .toList()).isEmpty();
    }

    // Resolving a stored role string is how the ACL turns a profile into
    // permissions; an unknown string must not resolve to a default role.
    @Test
    @DisplayName("An unrecognised role name resolves to nothing")
    void unknownRoleResolvesToNothing() {
        assertThat(AdminRole.fromKey("ANALYST")).isEmpty();
        assertThat(AdminRole.fromKey("MINT_ADMIN")).contains(AdminRole.MINT_ADMIN);
    }
}
