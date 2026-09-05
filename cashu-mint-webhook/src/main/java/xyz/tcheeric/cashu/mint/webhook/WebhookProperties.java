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
     * HMAC shared secret used to validate signatures on incoming webhooks.
     *
     * <p>Spec 001 FR-007 makes signature validation strict: when this value
     * is blank, {@link WebhookSignatureValidator#validate} fails closed
     * (returns {@code false}) — unsigned webhooks are NOT accepted.
     * Non-local Spring profiles refuse to boot at all when the secret is
     * unset, via {@link WebhookSecretStartupValidator}, so production
     * deployments never reach the strict-validator path with an empty
     * secret. The {@code local} profile is exempt from the boot guard so
     * developers can iterate, but the validator still rejects requests
     * unless the secret is configured.
     */
    private String sharedSecret = "";

    /**
     * Stable provider identifier used as the high half of the durable webhook
     * idempotency key {@code (provider, provider_event_id)} (FR-006). Defaults
     * to {@code "phoenixd"} for the existing Lightning deployment; override per
     * environment when a different gateway sources the webhooks.
     */
    private String provider = "phoenixd";

    /**
     * How far a webhook's {@code X-Webhook-Timestamp} may be from the receiver's clock, in
     * seconds.
     *
     * <p>An HMAC over the body alone is valid forever, so anyone who observes one delivery can
     * replay the identical bytes indefinitely (audit M-5). Binding the timestamp into the signed
     * material and refusing stale ones bounds that to this window. Five minutes is the usual
     * choice: wide enough for ordinary clock drift and retry delay, narrow enough that a captured
     * delivery is not a lasting capability.
     */
    private long timestampToleranceSeconds = 300;

    /**
     * Whether a webhook without a timestamp is refused.
     *
     * <p>Defaults false so that a sender not yet emitting the header keeps working; the signature
     * is still checked, it simply has no replay bound. Set true once every sender emits one.
     */
    private boolean requireTimestamp = false;

    public boolean hasSharedSecret() {
        return sharedSecret != null && !sharedSecret.isBlank();
    }
}
