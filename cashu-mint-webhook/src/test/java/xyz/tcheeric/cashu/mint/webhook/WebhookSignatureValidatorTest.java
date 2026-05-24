package xyz.tcheeric.cashu.mint.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WebhookSignatureValidator.
 *
 * <p>Spec 001 FR-007 removes the legacy skip-pass branch: a missing shared secret
 * causes validation to fail (the startup validator separately prevents non-local
 * profiles from booting in that state — see WebhookSecretStartupValidator).
 */
class WebhookSignatureValidatorTest {

    private WebhookProperties properties;
    private WebhookSignatureValidator validator;

    @BeforeEach
    void setUp() {
        properties = new WebhookProperties();
        validator = new WebhookSignatureValidator(properties);
    }

    private static final byte[] BODY = "{\"quote_id\":\"q1\",\"amount\":1000}".getBytes(StandardCharsets.UTF_8);

    @Test
    void validate_shouldReturnFalseWhenNoSecretConfigured() {
        // Given - no secret configured (FR-007 removes skip-pass; local-dev must set the secret)
        properties.setSharedSecret("");

        // When
        boolean result = validator.validate(BODY, null);

        // Then
        assertFalse(result);
    }

    @Test
    void validate_shouldReturnFalseWhenSecretConfiguredButNoSignature() {
        // Given
        properties.setSharedSecret("mysecret");

        // When
        boolean result = validator.validate(BODY, null);

        // Then
        assertFalse(result);
    }

    @Test
    void validate_shouldReturnFalseWhenSecretConfiguredAndEmptySignature() {
        // Given
        properties.setSharedSecret("mysecret");

        // When
        boolean result = validator.validate(BODY, "");

        // Then
        assertFalse(result);
    }

    @Test
    void validate_shouldReturnFalseForInvalidSignature() {
        // Given
        properties.setSharedSecret("mysecret");

        // When
        boolean result = validator.validate(BODY, "invalid-signature");

        // Then
        assertFalse(result);
    }

    @Test
    void validate_shouldReturnTrueForSignatureOverRawBody() throws Exception {
        // Spec 008 — a signature computed over the EXACT raw body bytes (the
        // way payment-adapter HttpMintWebhookForwarder signs) must verify.
        properties.setSharedSecret("mysecret");
        String sig = hmacBase64(BODY, "mysecret");

        assertTrue(validator.validate(BODY, sig));
    }

    @Test
    void validate_shouldReturnFalseWhenBodyTamperedAfterSigning() throws Exception {
        properties.setSharedSecret("mysecret");
        String sig = hmacBase64(BODY, "mysecret");
        byte[] tampered = "{\"quote_id\":\"q1\",\"amount\":1001}".getBytes(StandardCharsets.UTF_8);

        assertFalse(validator.validate(tampered, sig));
    }

    @Test
    void validate_shouldReturnFalseWhenSignedWithDifferentSecret() throws Exception {
        properties.setSharedSecret("mysecret");
        String sig = hmacBase64(BODY, "the-wrong-secret");

        assertFalse(validator.validate(BODY, sig));
    }

    private static String hmacBase64(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body));
    }

    @Test
    void isEnabled_shouldReturnFalseWhenNoSecret() {
        // Given
        properties.setSharedSecret("");

        // Then
        assertFalse(validator.isEnabled());
    }

    @Test
    void isEnabled_shouldReturnFalseWhenNullSecret() {
        // Given
        properties.setSharedSecret(null);

        // Then
        assertFalse(validator.isEnabled());
    }

    @Test
    void isEnabled_shouldReturnTrueWhenSecretConfigured() {
        // Given
        properties.setSharedSecret("mysecret");

        // Then
        assertTrue(validator.isEnabled());
    }
}
