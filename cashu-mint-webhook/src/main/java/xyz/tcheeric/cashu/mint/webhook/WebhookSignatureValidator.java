package xyz.tcheeric.cashu.mint.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
 * <p><b>Security:</b> Uses constant-time comparison to prevent timing attacks
 * (per Oracle Secure Coding Guidelines).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class WebhookSignatureValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final WebhookProperties properties;

    /**
     * Validate the webhook signature.
     *
     * @param notification the notification payload
     * @param signature    the signature from X-Webhook-Signature header
     * @return true if signature is present and verifies; false otherwise (including missing secret)
     */
    public boolean validate(PaymentNotification notification, String signature) {
        if (!properties.hasSharedSecret()) {
            log.warn("Webhook signature validation cannot proceed: shared secret not configured");
            return false;
        }

        if (signature == null || signature.isBlank()) {
            log.warn("Webhook signature missing for quoteId={}", notification.getQuoteId());
            return false;
        }

        try {
            String payload = MAPPER.writeValueAsString(notification);
            String expectedSignature = computeSignature(payload);

            boolean valid = MessageDigest.isEqual(
                    signature.getBytes(StandardCharsets.UTF_8),
                    expectedSignature.getBytes(StandardCharsets.UTF_8)
            );

            if (!valid) {
                log.warn("Webhook signature mismatch for quoteId={}", notification.getQuoteId());
            }

            return valid;

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize notification for signature validation", e);
            return false;
        }
    }

    /**
     * Compute HMAC-SHA256 signature for a payload.
     *
     * @param payload the JSON payload
     * @return Base64-encoded signature
     */
    private String computeSignature(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    properties.getSharedSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
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
