package xyz.tcheeric.cashu.mint.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

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

    @Test
    void validate_shouldReturnFalseWhenNoSecretConfigured() {
        // Given - no secret configured (FR-007 removes skip-pass; local-dev must set the secret)
        properties.setSharedSecret("");

        // When
        boolean result = validator.validate(createNotification(), null);

        // Then
        assertFalse(result);
    }

    @Test
    void validate_shouldReturnFalseWhenSecretConfiguredButNoSignature() {
        // Given
        properties.setSharedSecret("mysecret");

        // When
        boolean result = validator.validate(createNotification(), null);

        // Then
        assertFalse(result);
    }

    @Test
    void validate_shouldReturnFalseWhenSecretConfiguredAndEmptySignature() {
        // Given
        properties.setSharedSecret("mysecret");

        // When
        boolean result = validator.validate(createNotification(), "");

        // Then
        assertFalse(result);
    }

    @Test
    void validate_shouldReturnFalseForInvalidSignature() {
        // Given
        properties.setSharedSecret("mysecret");

        // When
        boolean result = validator.validate(createNotification(), "invalid-signature");

        // Then
        assertFalse(result);
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

    private PaymentNotification createNotification() {
        return PaymentNotification.builder()
                .quoteId("quote123")
                .paymentMethod("bolt11")
                .amount(1000)
                .preimage("preimage456")
                .paidAt(Instant.now())
                .build();
    }
}
