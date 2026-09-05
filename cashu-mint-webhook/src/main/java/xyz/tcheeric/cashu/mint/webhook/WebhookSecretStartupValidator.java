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
 * <p><b>Local profile</b>: this startup gate is bypassed for {@code local} so
 * developers can iterate on unrelated code without configuring a secret. The
 * exemption is for <em>startup only</em> — {@link WebhookSignatureValidator}
 * itself still fails closed when the secret is blank (signatures cannot
 * verify against an empty key), so local-mode developers either configure a
 * test secret or accept that the webhook endpoint will reject every delivery.
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
        if (!properties.isRequireTimestamp()) {
            // A signature with no replay bound is not much of a defence. With requireTimestamp
            // false an attacker who captured any historical delivery simply OMITS the
            // X-Webhook-Timestamp header: timestampWithinWindow(null) passes, signedPayload falls
            // back to the bare body, and the old body-only MAC verifies. The replay window is
            // infinite, so the spec-001 replay fix shipped switched off.
            //
            // The false default exists so a sender not yet emitting the header keeps working,
            // which is a migration concern and belongs to local development, not production.
            throw new IllegalStateException(
                    "cashu.mint.webhook.require-timestamp must be true in non-local profiles. "
                            + "With it false a caller can replay any captured delivery for ever "
                            + "by omitting the X-Webhook-Timestamp header, which makes the "
                            + "signature check no bar to replay at all. Set "
                            + "MINT_WEBHOOK_REQUIRE_TIMESTAMP=true once every sender emits the "
                            + "header, or run the local profile while migrating.");
        }
        log.info("Webhook signature validation enabled (shared secret configured, "
                + "timestamp required)");
    }
}
