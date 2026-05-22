package xyz.tcheeric.cashu.mint.proto.ports;

import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

/**
 * Resolves the stable {@code provider} identifier used as the high half of the
 * webhook idempotency key {@code (provider, provider_event_id)} (FR-006).
 *
 * <p>Spec 001 research R6: prefer {@link Gateway#getName()} (e.g.
 * {@code "phoenixd"}); fall back to the class simple name with a warning log
 * if the gateway returns a blank value. Class names are unstable across
 * refactors, so the warning makes the fallback visible.
 */
@Slf4j
public final class ProviderIdentifier {

    private ProviderIdentifier() {
    }

    /**
     * Returns a stable provider identifier for the given gateway.
     *
     * @param gateway the active payment-adapter gateway; must not be {@code null}
     * @return the gateway's reported name, or its class simple name as a fallback
     */
    public static String resolve(Gateway gateway) {
        if (gateway == null) {
            throw new IllegalArgumentException("gateway must not be null");
        }
        String name = gateway.getName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        String fallback = gateway.getClass().getSimpleName();
        log.warn("provider_identifier_fallback gateway_class={} (Gateway#getName() returned blank; "
                + "consider providing a stable identifier per spec 001 research R6)", fallback);
        return fallback;
    }
}
