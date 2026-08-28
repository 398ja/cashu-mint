package xyz.tcheeric.cashu.mint.admin.rest.nap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when NAP is off, because NAP is now the only authentication the
 * admin API has: booting without it would serve every endpoint to anyone who can
 * reach the port, and an unauthenticated admin API is worse than none.
 */
@Component
@ConditionalOnProperty(prefix = "nap", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NapRequiredGuard {

    public NapRequiredGuard() {
        throw new IllegalStateException(
            "nap.enabled must be true: NAP sessions are the only way into the admin API");
    }
}
