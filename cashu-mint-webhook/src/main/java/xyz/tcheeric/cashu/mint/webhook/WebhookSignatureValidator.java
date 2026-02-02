package xyz.tcheeric.cashu.mint.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Validates HMAC signatures on incoming webhooks.
 *
 * <p>If no secret is configured, validation is skipped (for development).
 * In production, always configure MINT_WEBHOOK_SECRET.
 *
 * <p><b>Security:</b> Uses constant-time comparison to prevent timing attacks
 * (per Oracle Secure Coding Guidelines).
 */
@Slf4j
@Component
public final class WebhookSignatureValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Value("${webhook.secret:}")
    private String webhookSecret;

    /**
     * Validate the webhook signature.
     *
     * @param notification the notification payload
     * @param signature    the signature from X-Webhook-Signature header
     * @return true if signature is valid or validation is disabled
     */
    public boolean validate(PaymentNotification notification, String signature) {
        // If no secret configured, skip validation (development mode)
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.debug("Webhook signature validation disabled (no secret configured)");
            return true;
        }

        // If secret is configured but no signature provided, reject
        if (signature == null || signature.isBlank()) {
            log.warn("Webhook signature missing but secret is configured");
            return false;
        }

        try {
            String payload = MAPPER.writeValueAsString(notification);
            String expectedSignature = computeSignature(payload);

            // Constant-time comparison to prevent timing attacks
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
                    webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
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
        return webhookSecret != null && !webhookSecret.isBlank();
    }
}
