package xyz.tcheeric.cashu.mint.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for WebhookSignatureValidator.
 */
class WebhookSignatureValidatorTest {

    private WebhookSignatureValidator validator;

    @BeforeEach
    void setUp() {
        validator = new WebhookSignatureValidator();
    }

    @Test
    void validate_shouldReturnTrueWhenNoSecretConfigured() {
        // Given - no secret configured
        ReflectionTestUtils.setField(validator, "webhookSecret", "");
        PaymentNotification notification = createNotification();

        // When
        boolean result = validator.validate(notification, null);

        // Then
        assertTrue(result);
    }

    @Test
    void validate_shouldReturnFalseWhenSecretConfiguredButNoSignature() {
        // Given
        ReflectionTestUtils.setField(validator, "webhookSecret", "mysecret");
        PaymentNotification notification = createNotification();

        // When
        boolean result = validator.validate(notification, null);

        // Then
        assertFalse(result);
    }

    @Test
    void validate_shouldReturnFalseWhenSecretConfiguredAndEmptySignature() {
        // Given
        ReflectionTestUtils.setField(validator, "webhookSecret", "mysecret");
        PaymentNotification notification = createNotification();

        // When
        boolean result = validator.validate(notification, "");

        // Then
        assertFalse(result);
    }

    @Test
    void validate_shouldReturnFalseForInvalidSignature() {
        // Given
        ReflectionTestUtils.setField(validator, "webhookSecret", "mysecret");
        PaymentNotification notification = createNotification();

        // When
        boolean result = validator.validate(notification, "invalid-signature");

        // Then
        assertFalse(result);
    }

    @Test
    void isEnabled_shouldReturnFalseWhenNoSecret() {
        // Given
        ReflectionTestUtils.setField(validator, "webhookSecret", "");

        // Then
        assertFalse(validator.isEnabled());
    }

    @Test
    void isEnabled_shouldReturnFalseWhenNullSecret() {
        // Given
        ReflectionTestUtils.setField(validator, "webhookSecret", null);

        // Then
        assertFalse(validator.isEnabled());
    }

    @Test
    void isEnabled_shouldReturnTrueWhenSecretConfigured() {
        // Given
        ReflectionTestUtils.setField(validator, "webhookSecret", "mysecret");

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
