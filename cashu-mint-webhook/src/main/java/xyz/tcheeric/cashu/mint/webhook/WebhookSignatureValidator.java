package xyz.tcheeric.cashu.mint.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Validates HMAC signatures on incoming webhooks. Spec 001 FR-007 makes the
 * shared secret mandatory in non-local profiles via
 * {@link WebhookSecretStartupValidator}; this class is strict: a missing or
 * blank secret causes validation to fail (no silent pass-through). Local
 * development needs to configure {@code cashu.mint.webhook.shared-secret}
 * (or {@code MINT_WEBHOOK_SECRET}) the same way production does.
 *
 * <p>This class implements the cashu-mint side of the webhook contract;
 * sibling spec NUT references for the controller it gates are documented on
 * {@link PaymentWebhookController}. There is no canonical NUT for webhook
 * signature validation today — the contract lives in spec 001 § FR-007
 * (Principle VI of the cashu-mint Constitution v1.1.0).
 *
 * <p><b>Security:</b> Uses constant-time comparison to prevent timing attacks
 * (per Oracle Secure Coding Guidelines).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class WebhookSignatureValidator {

    private final WebhookProperties properties;

    /**
     * Validate the webhook signature over the EXACT raw request body bytes.
     *
     * <p>Spec 008 — the signature MUST be computed over the bytes the sender
     * actually signed (what {@code payment-adapter} POSTs), not over a
     * re-serialised DTO. Re-serialising the deserialised
     * {@link PaymentNotification} produced different JSON (camelCase / field
     * order / Instant format) than the adapter's snake_case payload, so a
     * correctly-signed webhook always failed. HMAC-ing the raw body is
     * serialization-agnostic and matches the adapter byte-for-byte.
     *
     * @param rawBody   the exact request body bytes as received
     * @param signature the signature from the X-Webhook-Signature header
     * @return true if signature is present and verifies; false otherwise (including missing secret)
     */
    public boolean validate(byte[] rawBody, String signature) {
        if (!properties.hasSharedSecret()) {
            log.warn("Webhook signature validation cannot proceed: shared secret not configured");
            return false;
        }

        if (signature == null || signature.isBlank()) {
            log.warn("Webhook signature missing");
            return false;
        }

        if (rawBody == null) {
            log.warn("Webhook signature validation cannot proceed: empty request body");
            return false;
        }

        String expectedSignature = computeSignature(rawBody);
        boolean valid = MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8),
                expectedSignature.getBytes(StandardCharsets.UTF_8)
        );

        if (!valid) {
            log.warn("Webhook signature mismatch");
        }

        return valid;
    }

    /**
     * Compute the Base64 HMAC-SHA256 of the raw payload bytes.
     */
    private String computeSignature(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    properties.getSharedSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(payload);
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            log.error("Failed to compute webhook signature", e);
            throw new RuntimeException("Failed to compute signature", e);
        }
    }

    /**
     * Check if signature validation is enabled.
     *
     * @return true if a secret is configured
     */
    public boolean isEnabled() {
        return properties.hasSharedSecret();
    }
}
