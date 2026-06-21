package xyz.tcheeric.cashu.mint.rest.service.trace;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.ledger.trace.publisher.spring.TracePublisherProperties;

/**
 * Spec 036 US3 — verifies the producer fails closed at boot when enabled but
 * misconfigured, and starts cleanly when fully configured.
 */
class TraceProducerGuardTest {

    private TracePublisherProperties props(String key, List<String> relays) {
        TracePublisherProperties p = new TracePublisherProperties();
        p.setEnabled(true);
        p.setPrivateKeyHex(key);
        p.setRelays(relays);
        return p;
    }

    // Missing signing key => startup aborts.
    @Test
    void blankPrivateKey_failsClosed() {
        assertThatThrownBy(() -> new TraceProducerGuard(
                props("", List.of("wss://relay")), "https://mint.test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("private-key-hex");
    }

    // Empty relay set => startup aborts.
    @Test
    void noRelays_failsClosed() {
        assertThatThrownBy(() -> new TraceProducerGuard(
                props("abc123", List.of()), "https://mint.test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("relays");
    }

    // Missing mint URL => startup aborts.
    @Test
    void blankMintUrl_failsClosed() {
        assertThatThrownBy(() -> new TraceProducerGuard(
                props("abc123", List.of("wss://relay")), "  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cashu.mint.url");
    }

    // Fully configured => constructs without error.
    @Test
    void fullyConfigured_startsClean() {
        assertThatCode(() -> new TraceProducerGuard(
                props("abc123", List.of("wss://relay")), "https://mint.test"))
                .doesNotThrowAnyException();
    }
}
