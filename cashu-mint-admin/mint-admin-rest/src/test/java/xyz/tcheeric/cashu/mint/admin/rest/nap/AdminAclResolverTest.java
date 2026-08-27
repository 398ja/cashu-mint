package xyz.tcheeric.cashu.mint.admin.rest.nap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository.OperatorAccessAccount;
import xyz.tcheeric.cashu.mint.admin.domain.AdminPermission;
import xyz.tcheeric.cashu.mint.admin.domain.AdminRole;
import xyz.tcheeric.nap.core.AclDecision;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAclResolverTest {

    private static final String SUPER_ADMIN_PUBKEY =
        "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d";
    private static final String OPERATOR_PUBKEY =
        "e8b487c079b0f67c695ae6c4c2552a47f38adfa2533cc5926bd2c102942fdcb7";

    private final OperatorAccessRepository repository = mock(OperatorAccessRepository.class);

    // The Super Administrator is configuration, not data: resolving them must not
    // touch the operator store, or a database outage locks out the one account
    // that can recover the deployment.
    @Test
    @DisplayName("Super Administrator resolves without consulting the operator store")
    void superAdminResolvesWithoutDatabase() {
        final AclDecision decision = resolver().resolve("npub1anything", SUPER_ADMIN_PUBKEY);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.roles()).containsExactly(AdminRole.SUPER_ADMIN.key());
        assertThat(decision.permissions())
            .containsExactlyInAnyOrderElementsOf(allPermissionKeys());
        verify(repository, never()).findByPubkey(anyString());
    }

    // Configuration wins over data. A Super Administrator whose profile was
    // suspended must still get in — otherwise an Operator with USER_ADMIN could
    // suspend the account that outranks them and lock the deployment.
    @Test
    @DisplayName("Configured Super Administrator outranks a suspended profile")
    void configuredSuperAdminOutranksSuspendedProfile() {
        when(repository.findByPubkey(SUPER_ADMIN_PUBKEY))
            .thenReturn(Optional.of(account(Set.of("MINT_ADMIN"), false)));

        final AclDecision decision = resolver().resolve("npub1super", SUPER_ADMIN_PUBKEY);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.roles()).containsExactly(AdminRole.SUPER_ADMIN.key());
    }

    // Authenticating proves who you are, not that you may do anything. An npub
    // with no profile gets no default role.
    @Test
    @DisplayName("An npub with no operator profile is refused")
    void npubWithoutProfileIsRefused() {
        when(repository.findByPubkey(OPERATOR_PUBKEY)).thenReturn(Optional.empty());

        final AclDecision decision = resolver().resolve("npub1stranger", OPERATOR_PUBKEY);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.roles()).isEmpty();
        assertThat(decision.permissions()).isEmpty();
    }

    // A suspended Operator is a certain denial, so every session they hold is
    // revoked — unlike an unreadable store, which denies one request and no more.
    @Test
    @DisplayName("A suspended operator is refused and their sessions revoked")
    void suspendedOperatorIsRefused() {
        when(repository.findByPubkey(OPERATOR_PUBKEY))
            .thenReturn(Optional.of(account(Set.of("OPS_ADMIN"), false)));

        final AclDecision decision = resolver().resolve("npub1suspended", OPERATOR_PUBKEY);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.revokeSessions()).isTrue();
    }

    // An active Operator gets exactly the permissions their roles carry, so the
    // session endpoint can report what the caller may do.
    @Test
    @DisplayName("An active operator carries the permissions of their roles")
    void activeOperatorCarriesRolePermissions() {
        when(repository.findByPubkey(OPERATOR_PUBKEY))
            .thenReturn(Optional.of(account(Set.of("MINT_ADMIN"), true)));

        final AclDecision decision = resolver().resolve("npub1operator", OPERATOR_PUBKEY);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.roles()).containsExactly("MINT_ADMIN");
        assertThat(decision.permissions()).containsExactlyInAnyOrder(
            AdminPermission.MINT_LIFECYCLE.key(), AdminPermission.AUDIT_READ.key());
    }

    // A role string the store holds but this build does not declare must not
    // widen access; it contributes nothing rather than falling back to a default.
    @Test
    @DisplayName("An unrecognised stored role grants no permissions")
    void unknownStoredRoleGrantsNothing() {
        when(repository.findByPubkey(OPERATOR_PUBKEY))
            .thenReturn(Optional.of(account(Set.of("ANALYST"), true)));

        final AclDecision decision = resolver().resolve("npub1analyst", OPERATOR_PUBKEY);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.permissions()).isEmpty();
        // Nor is it reported as held: a role carrying no permission is entitlement nobody has.
        assertThat(decision.roles()).isEmpty();
    }

    // The pubkey NAP hands over is lower-case hex; a configured npub decoded to
    // upper-case must still match, or the Super Administrator silently loses access.
    @Test
    @DisplayName("Super Administrator matching ignores hex case")
    void superAdminMatchIgnoresHexCase() {
        final AdminAclResolver upperCased =
            new AdminAclResolver(SUPER_ADMIN_PUBKEY.toUpperCase(), repository);

        assertThat(upperCased.resolve("npub1super", SUPER_ADMIN_PUBKEY).allowed()).isTrue();
    }

    private AdminAclResolver resolver() {
        return new AdminAclResolver(SUPER_ADMIN_PUBKEY, repository);
    }

    private static OperatorAccessAccount account(final Set<String> roles, final boolean active) {
        return new OperatorAccessAccount("11111111-1111-1111-1111-111111111111", "Ops", "ops@example.com",
            roles, active, 0, "hash", Instant.EPOCH, OPERATOR_PUBKEY);
    }

    private static List<String> allPermissionKeys() {
        final List<String> keys = new ArrayList<>();
        for (final AdminPermission permission : AdminPermission.values()) {
            keys.add(permission.key());
        }
        return keys;
    }
}
