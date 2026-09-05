package xyz.tcheeric.cashu.mint.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A webhook signature has to bind to a moment, and be compared as bytes.
 *
 * <p>The MAC covered only the body (audit M-5), so a signature was valid forever: anyone who
 * observed one delivery could replay the identical bytes indefinitely and it still verified.
 * Separately the comparison ran over base64 <em>text</em>, which compares padding and any
 * whitespace the sender included, neither of which is part of the MAC.
 */
@DisplayName("Webhook signature validation")
class WebhookSignatureReplayTest {

    private static final String SECRET = "test-shared-secret-0123456789abcdef";

    private WebhookProperties properties;
    private WebhookSignatureValidator validator;

    @BeforeEach
    void setUp() {
        properties = new WebhookProperties();
        properties.setSharedSecret(SECRET);
        validator = new WebhookSignatureValidator(properties);
    }

    private static String sign(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(
                mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static String now() {
        return String.valueOf(System.currentTimeMillis() / 1000L);
    }

    @Nested
    @DisplayName("timestamp binding")
    class TimestampBinding {

        @Test
        @DisplayName("a fresh timestamped delivery verifies")
        void freshDeliveryVerifies() throws Exception {
            String body = "{\"quoteId\":\"q1\"}";
            String ts = now();

            assertThat(validator.validate(body.getBytes(StandardCharsets.UTF_8),
                    sign(ts + "." + body), ts)).isTrue();
        }

        @Test
        @DisplayName("a delivery replayed after the window is refused")
        void staleDeliveryIsRefused() throws Exception {
            String body = "{\"quoteId\":\"q1\"}";
            // Signed an hour ago: the signature is still cryptographically correct, which is the
            // whole point. Only the timestamp makes it refusable.
            String oldTs = String.valueOf((System.currentTimeMillis() / 1000L) - 3600);

            assertThat(validator.validate(body.getBytes(StandardCharsets.UTF_8),
                    sign(oldTs + "." + body), oldTs))
                    .as("a captured delivery must not stay replayable forever")
                    .isFalse();
        }

        @Test
        @DisplayName("moving the timestamp forward invalidates the signature")
        void timestampIsInsideTheMac() throws Exception {
            String body = "{\"quoteId\":\"q1\"}";
            String oldTs = String.valueOf((System.currentTimeMillis() / 1000L) - 3600);
            String signedWithOldTs = sign(oldTs + "." + body);

            // The attacker refreshes the header to get inside the window. Since the timestamp is
            // part of the signed material, the signature no longer matches.
            assertThat(validator.validate(body.getBytes(StandardCharsets.UTF_8),
                    signedWithOldTs, now()))
                    .as("the timestamp must be inside the MAC, not merely alongside it")
                    .isFalse();
        }

        @Test
        @DisplayName("a non-numeric timestamp is refused")
        void nonNumericTimestampIsRefused() throws Exception {
            String body = "{\"quoteId\":\"q1\"}";

            assertThat(validator.validate(body.getBytes(StandardCharsets.UTF_8),
                    sign("abc." + body), "abc")).isFalse();
        }

        @Test
        @DisplayName("an untimestamped delivery still verifies by default")
        void untimestampedStillWorks() throws Exception {
            // Backwards compatibility: a sender that has not been updated keeps working, with
            // the signature checked but no replay bound.
            String body = "{\"quoteId\":\"q1\"}";

            assertThat(validator.validate(body.getBytes(StandardCharsets.UTF_8),
                    sign(body), null)).isTrue();
        }

        @Test
        @DisplayName("an untimestamped delivery is refused when require-timestamp is set")
        void untimestampedRefusedWhenRequired() throws Exception {
            properties.setRequireTimestamp(true);
            String body = "{\"quoteId\":\"q1\"}";

            assertThat(validator.validate(body.getBytes(StandardCharsets.UTF_8),
                    sign(body), null)).isFalse();
        }
    }

    @Nested
    @DisplayName("signature comparison")
    class SignatureComparison {

        @Test
        @DisplayName("a wrong signature is refused")
        void wrongSignatureRefused() throws Exception {
            String body = "{\"quoteId\":\"q1\"}";
            String ts = now();

            assertThat(validator.validate(body.getBytes(StandardCharsets.UTF_8),
                    sign(ts + ".{\"quoteId\":\"other\"}"), ts)).isFalse();
        }

        @Test
        @DisplayName("a signature with trailing whitespace still verifies")
        void whitespaceToleratedAfterDecoding() throws Exception {
            // Comparing base64 text made this fail; comparing decoded bytes makes it pass,
            // because whitespace is not part of the MAC.
            String body = "{\"quoteId\":\"q1\"}";
            String ts = now();

            assertThat(validator.validate(body.getBytes(StandardCharsets.UTF_8),
                    sign(ts + "." + body) + "  ", ts)).isTrue();
        }

        @Test
        @DisplayName("a signature that is not base64 is refused rather than throwing")
        void nonBase64Refused() {
            String body = "{\"quoteId\":\"q1\"}";

            assertThat(validator.validate(body.getBytes(StandardCharsets.UTF_8),
                    "!!!not base64!!!", now())).isFalse();
        }

        @Test
        @DisplayName("a missing shared secret fails closed")
        void missingSecretFailsClosed() throws Exception {
            properties.setSharedSecret("");
            String body = "{\"quoteId\":\"q1\"}";

            assertThat(validator.validate(body.getBytes(StandardCharsets.UTF_8),
                    sign(body), null)).isFalse();
        }
    }
}
