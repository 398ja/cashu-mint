package xyz.tcheeric.cashu.mint.admin.rest.nap;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Clears the session {@link TestNapSessions} seats on the calling thread.
 *
 * <p>MockMvc dispatches on the test thread, so the authentication survives the request and
 * would otherwise leak into the next test — which then passes whether or not the endpoint
 * checks anything.
 */
public class NapSessionCleanup implements AfterEachCallback {

    @Override
    public void afterEach(final ExtensionContext context) {
        SecurityContextHolder.clearContext();
    }
}
