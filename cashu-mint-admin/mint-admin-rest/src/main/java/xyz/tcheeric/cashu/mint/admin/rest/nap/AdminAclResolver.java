package xyz.tcheeric.cashu.mint.admin.rest.nap;

import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository.OperatorAccessAccount;
import xyz.tcheeric.cashu.mint.admin.domain.AdminPermission;
import xyz.tcheeric.cashu.mint.admin.domain.AdminRole;
import xyz.tcheeric.nap.core.AclDecision;
import xyz.tcheeric.nap.server.AclResolver;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.EnumSet;
import java.util.TreeSet;

/**
 * Decides what an authenticated npub may do.
 *
 * <p>The configured Super Administrator is checked first and never reaches the
 * database: they are configuration, and a store outage must not lock out the one
 * account that can recover the deployment. Everyone else is an Operator profile
 * or nobody — authenticating proves identity, not entitlement, so an npub with
 * no profile is refused rather than given a default role.
 *
 * <p>NAP's own {@code AclStore} and {@code nap_acl} table are deliberately
 * unused: {@code AclStore} cannot list, update or delete, so it cannot express
 * Operator management, and two sources of truth for one fact is worse than none.
 */
public class AdminAclResolver implements AclResolver {

    private final String superAdminPubkey;
    private final OperatorAccessRepository operatorAccessRepository;

    public AdminAclResolver(final String superAdminPubkey,
                            final OperatorAccessRepository operatorAccessRepository) {
        this.superAdminPubkey = Objects.requireNonNull(superAdminPubkey, "super admin pubkey").toLowerCase();
        this.operatorAccessRepository = Objects.requireNonNull(operatorAccessRepository,
            "operator access repository");
    }

    @Override
    public AclDecision resolve(final String npub, final String pubkey) {
        if (pubkey != null && superAdminPubkey.equals(pubkey.toLowerCase())) {
            return AclDecision.allowed(List.of(AdminRole.SUPER_ADMIN.key()),
                permissionKeys(Set.of(AdminRole.SUPER_ADMIN)));
        }

        return operatorAccessRepository.findByPubkey(pubkey)
            .map(AdminAclResolver::decide)
            .orElseGet(() -> AclDecision.denied("no_operator_profile"));
    }

    private static AclDecision decide(final OperatorAccessAccount account) {
        if (!account.active()) {
            // A certain denial, so every session this operator holds goes with it.
            return AclDecision.denied("operator_suspended", true);
        }
        final Set<AdminRole> roles = EnumSet.noneOf(AdminRole.class);
        for (final String role : account.roles()) {
            AdminRole.fromKey(role).ifPresent(roles::add);
        }
        // Only roles this application knows are reported: an unrecognised name grants
        // no permission, so reporting it would describe entitlement nobody has.
        return AclDecision.allowed(roles.stream().map(AdminRole::key).toList(), permissionKeys(roles));
    }

    private static List<String> permissionKeys(final Set<AdminRole> roles) {
        final Set<String> keys = new TreeSet<>();
        for (final AdminRole role : roles) {
            for (final AdminPermission permission : role.permissions()) {
                keys.add(permission.key());
            }
        }
        return List.copyOf(keys);
    }
}
