package xyz.tcheeric.cashu.mint.webhook;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binding for {@code cashu.mint.webhook.*}. The shared secret is intentionally
 * left as a plain string here; non-local profiles validate it at boot via
 * {@link WebhookSecretStartupValidator} so that staging/prod fail fast when the
 * secret is missing.
 *
 * <p>Spec 001: FR-007 + research R5.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "cashu.mint.webhook")
public class WebhookProperties {

    /**
     * HMAC shared secret used to validate signatures on incoming webhooks. When
     * blank, signatures are not enforced (development convenience); in non-local
     * profiles {@link WebhookSecretStartupValidator} prevents the context from
     * starting in that state.
     */
    private String sharedSecret = "";

    /**
     * Stable provider identifier used as the high half of the durable webhook
     * idempotency key {@code (provider, provider_event_id)} (FR-006). Defaults
     * to {@code "phoenixd"} for the existing Lightning deployment; override per
     * environment when a different gateway sources the webhooks.
     */
    private String provider = "phoenixd";

    public boolean hasSharedSecret() {
        return sharedSecret != null && !sharedSecret.isBlank();
    }
}
