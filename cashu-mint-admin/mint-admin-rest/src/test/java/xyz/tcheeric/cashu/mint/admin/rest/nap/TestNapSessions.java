package xyz.tcheeric.cashu.mint.admin.rest.nap;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import xyz.tcheeric.cashu.mint.admin.domain.AdminPermission;
import xyz.tcheeric.cashu.mint.admin.domain.AdminRole;
import xyz.tcheeric.nap.core.SessionRecord;
import xyz.tcheeric.nap.spring.filter.NapSessionFilter.NapAuthenticationToken;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Puts an authenticated NAP session on a MockMvc request.
 *
 * <p>MockMvc dispatches on the calling thread and {@code NapSessionFilter} passes
 * an already-authenticated request straight through, so seating the token is
 * enough — a full handshake per request would only re-test the handshake, which
 * {@link AdminNapHandshakeTest} already does end to end.
 */
public final class TestNapSessions {

    private TestNapSessions() {
    }

    /** A session holding every permission. */
    public static RequestPostProcessor superAdmin() {
        return session(AdminRole.SUPER_ADMIN,
            Arrays.stream(AdminPermission.values()).map(AdminPermission::key).toList());
    }

    /** A session holding only the permissions the given role carries. */
    public static RequestPostProcessor role(final AdminRole role) {
        return session(role, role.permissions().stream().map(AdminPermission::key).toList());
    }

    private static RequestPostProcessor session(final AdminRole role, final List<String> permissions) {
        return request -> {
            final long now = Instant.now().getEpochSecond();
            SecurityContextHolder.getContext().setAuthentication(new NapAuthenticationToken(
                SessionRecord.create("test-session", "test-challenge", "test-token",
                    "npub10xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqpkge6d",
                    "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
                    List.of(role.key()), permissions, now, now + 900)));
            return request;
        };
    }
}
