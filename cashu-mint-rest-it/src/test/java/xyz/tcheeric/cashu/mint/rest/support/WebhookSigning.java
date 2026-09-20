package xyz.tcheeric.cashu.mint.rest.support;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs webhook deliveries the way a real sender does.
 *
 * <p>The mint requires two things on {@code /webhook/**}: an HMAC-SHA256
 * signature in {@code X-Webhook-Signature}, and — because
 * {@code cashu.mint.webhook.require-timestamp} defaults to {@code true} —
 * a {@code X-Webhook-Timestamp} that is bound <em>into</em> the MAC. The
 * signed payload is {@code timestamp + "." + body}, not the body alone,
 * which is what makes an observed delivery unusable as a replay.
 *
 * <p>This exists because several ITs were written before that requirement
 * landed and were silently failing afterwards: some sent no signature at
 * all, and one signed the bare body, which verifies only while the
 * timestamp requirement is off. Each had its own private copy of the
 * scheme, so there was no single place that could be kept correct. Tests
 * that need to prove rejection should pass a deliberately wrong value
 * rather than reimplementing the algorithm.
 */
public final class WebhookSigning {

    /** Matches {@code cashu.mint.webhook.shared-secret} in application-test.properties. */
    public static final String IT_SHARED_SECRET = "it-shared-secret";

    private WebhookSigning() {
    }

    /** A timestamp of "now" in the epoch-seconds form the validator parses. */
    public static String now() {
        return Long.toString(Instant.now().getEpochSecond());
    }

    /**
     * Signature over {@code timestamp + "." + body}, matching
     * {@code WebhookSignatureValidator#signedPayload}.
     */
    public static String sign(String body, String timestamp) {
        return sign(body, timestamp, IT_SHARED_SECRET);
    }

    public static String sign(String body, String timestamp, String secret) {
        String payload = (timestamp == null || timestamp.isBlank())
                ? body
                : timestamp.trim() + "." + body;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("could not sign webhook body", e);
        }
    }
}
