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
        return validate(rawBody, signature, null);
    }

    /**
     * Validate the signature, and the timestamp when the sender supplies one.
     *
     * @param rawBody   the exact request body bytes as received
     * @param signature the signature from the X-Webhook-Signature header
     * @param timestamp the value of the X-Webhook-Timestamp header, or null when absent
     * @return true if the signature verifies and the timestamp is within the accepted window
     */
    public boolean validate(byte[] rawBody, String signature, String timestamp) {
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

        if (!timestampWithinWindow(timestamp)) {
            return false;
        }

        // Compare decoded bytes, not base64 text (audit M-5). Two different signatures can share
        // a base64 prefix while differing in the bytes, and comparing the text also compares
        // padding and any whitespace the sender happened to include, which is not part of the
        // MAC. Decoding first means the comparison is over the 32 bytes that actually matter.
        byte[] presented = decodeBase64(signature);
        if (presented == null) {
            log.warn("Webhook signature is not valid base64");
            return false;
        }

        byte[] expected = computeSignatureBytes(signedPayload(rawBody, timestamp));
        boolean valid = MessageDigest.isEqual(presented, expected);

        if (!valid) {
            log.warn("Webhook signature mismatch");
        }

        return valid;
    }

    /**
     * Whether the sender's timestamp is close enough to now.
     *
     * <p>Without this, a signature is valid forever: anyone who observes one delivery can replay
     * the identical bytes indefinitely and the MAC still verifies, because nothing in the signed
     * material changes (audit M-5). Binding a timestamp into the signed payload and refusing
     * stale ones bounds that window.
     *
     * <p>A missing timestamp is accepted when {@code require-timestamp} is false, which is the
     * default so that a sender that has not been updated yet keeps working. Deployments whose
     * senders all send one should set it true, at which point replay outside the window is
     * refused outright.
     */
    private boolean timestampWithinWindow(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            if (properties.isRequireTimestamp()) {
                log.warn("Webhook timestamp missing and cashu.mint.webhook.require-timestamp=true");
                return false;
            }
            return true;
        }
        final long sent;
        try {
            sent = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            log.warn("Webhook timestamp is not an integer");
            return false;
        }
        long skewSeconds = Math.abs((System.currentTimeMillis() / 1000L) - sent);
        if (skewSeconds > properties.getTimestampToleranceSeconds()) {
            log.warn("Webhook timestamp outside the accepted window: skew={}s tolerance={}s",
                    skewSeconds, properties.getTimestampToleranceSeconds());
            return false;
        }
        return true;
    }

    /**
     * The bytes the MAC covers: the timestamp and the body, when a timestamp is present.
     *
     * <p>The timestamp has to be inside the signed material, otherwise an attacker simply edits
     * the header and replays.
     */
    private byte[] signedPayload(byte[] rawBody, String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return rawBody;
        }
        byte[] prefix = (timestamp.trim() + ".").getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[prefix.length + rawBody.length];
        System.arraycopy(prefix, 0, combined, 0, prefix.length);
        System.arraycopy(rawBody, 0, combined, prefix.length, rawBody.length);
        return combined;
    }

    private static byte[] decodeBase64(String value) {
        try {
            return Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Compute the Base64 HMAC-SHA256 of the raw payload bytes.
     */
    private String computeSignature(byte[] payload) {
        return Base64.getEncoder().encodeToString(computeSignatureBytes(payload));
    }

    private byte[] computeSignatureBytes(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    properties.getSharedSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            return mac.doFinal(payload);
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
