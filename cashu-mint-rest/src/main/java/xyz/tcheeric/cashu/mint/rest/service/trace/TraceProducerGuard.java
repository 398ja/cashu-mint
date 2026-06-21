package xyz.tcheeric.cashu.mint.rest.service.trace;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.ledger.trace.publisher.spring.TracePublisherProperties;

/**
 * Spec 036 — fail-closed boot guard for the trace producer.
 *
 * <p>A producer that is enabled but cannot sign or address a relay would
 * silently fail to emit — worse than being off. So when tracing is enabled this
 * guard fails application startup unless the signing key, the relay set, and the
 * mint URL are all present (mirrors the constitution's webhook-secret
 * fail-closed rule, Principle VI / Security Requirements).
 *
 * <p>Validation runs in the constructor, so a misconfiguration aborts context
 * refresh. The bean only exists when {@code cashu.trace.publisher.enabled=true},
 * so the guard is inert when tracing is disabled.
 */
@Component
@ConditionalOnProperty(prefix = "cashu.trace.publisher", name = "enabled", havingValue = "true")
public class TraceProducerGuard {

    public TraceProducerGuard(TracePublisherProperties properties,
                              @Value("${cashu.mint.url:}") String mintUrl) {
        if (isBlank(properties.getPrivateKeyHex())) {
            throw new IllegalStateException(
                    "cashu.trace.publisher.private-key-hex must be set when cashu.trace.publisher.enabled=true");
        }
        List<String> relays = properties.getRelays();
        if (relays == null || relays.stream().allMatch(TraceProducerGuard::isBlank)) {
            throw new IllegalStateException(
                    "cashu.trace.publisher.relays must list at least one relay when cashu.trace.publisher.enabled=true");
        }
        if (isBlank(mintUrl)) {
            throw new IllegalStateException(
                    "cashu.mint.url must be set when cashu.trace.publisher.enabled=true (trace events are attributed to it)");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
