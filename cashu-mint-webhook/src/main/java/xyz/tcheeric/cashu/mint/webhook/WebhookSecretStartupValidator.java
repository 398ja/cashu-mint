package xyz.tcheeric.cashu.mint.webhook;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Boot-time gate that fails the Spring context in any non-{@code local} profile
 * when the webhook shared secret is missing. Implements FR-007 + SC-005: mint
 * startup MUST fail in {@code staging} and {@code prod} profiles when the
 * webhook secret is unset.
 *
 * <p>The {@code local} profile is exempt so developers can iterate without a
 * configured HMAC secret.
 */
@Slf4j
@Component
@Profile("!local")
@RequiredArgsConstructor
public class WebhookSecretStartupValidator {

    private final WebhookProperties properties;

    @PostConstruct
    void enforceSharedSecret() {
        if (!properties.hasSharedSecret()) {
            throw new IllegalStateException(
                    "cashu.mint.webhook.shared-secret is required in non-local profiles "
                            + "(spec 001 FR-007 / SC-005). Set MINT_WEBHOOK_SECRET or "
                            + "cashu.mint.webhook.shared-secret in the application properties.");
        }
        log.info("Webhook signature validation enabled (shared secret configured)");
    }
}
