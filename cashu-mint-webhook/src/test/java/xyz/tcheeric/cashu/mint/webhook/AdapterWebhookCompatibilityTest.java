package xyz.tcheeric.cashu.mint.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 008 — proves the cashu-mint webhook layer is compatible with the
 * exact on-the-wire payload produced and signed by {@code payment-adapter}
 * {@code HttpMintWebhookForwarder}, closing the adapter↔mint contract gap.
 *
 * <p>{@link #GOLDEN_BODY} is a representative adapter payload: snake_case
 * field names ({@code quote_id}, {@code payment_method}, {@code receipt_id},
 * {@code customer_pubkey}, {@code paid_at}), a {@code unit} field, ISO-8601
 * {@code paid_at}, and a {@code customer_pubkey} the mint doesn't consume.
 * The signature is {@code Base64(HmacSHA256(GOLDEN_BODY_utf8, secret))} — the
 * same algorithm {@code HttpMintWebhookForwarder.computeSignature} uses.
 *
 * <p>If the mint regressed to re-serialising the DTO (the original bug) the
 * raw-body assertion would fail, since the camelCase re-serialisation never
 * equals these snake_case bytes.
 */
class AdapterWebhookCompatibilityTest {

    private static final String TEST_SECRET = "it-shared-secret";

    /** A real adapter-shaped payload (snake_case + unit + customer_pubkey). */
    private static final String GOLDEN_BODY =
            "{\"quote_id\":\"q-golden-1\","
            + "\"payment_method\":\"bolt11\","
            + "\"amount\":1000,"
            + "\"unit\":\"sat\","
            + "\"preimage\":\"preimage-golden-abc\","
            + "\"customer_pubkey\":\"02aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\","
            + "\"paid_at\":\"2026-05-25T12:00:00Z\"}";

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void validator_accepts_signature_over_the_raw_adapter_body() throws Exception {
        WebhookProperties props = new WebhookProperties();
        props.setSharedSecret(TEST_SECRET);
        WebhookSignatureValidator validator = new WebhookSignatureValidator(props);

        byte[] rawBody = GOLDEN_BODY.getBytes(StandardCharsets.UTF_8);
        String signature = hmacBase64(rawBody, TEST_SECRET);

        assertThat(validator.validate(rawBody, signature))
                .as("the mint must HMAC the raw adapter body, not a re-serialised DTO")
                .isTrue();
    }

    @Test
    void mint_dto_deserialises_every_adapter_field() throws Exception {
        PaymentNotification n = mapper.readValue(GOLDEN_BODY, PaymentNotification.class);

        assertThat(n.getQuoteId()).isEqualTo("q-golden-1");          // quote_id
        assertThat(n.getPaymentMethod()).isEqualTo("bolt11");        // payment_method
        assertThat(n.getAmount()).isEqualTo(1000);
        assertThat(n.getUnit()).isEqualTo("sat");
        assertThat(n.getPreimage()).isEqualTo("preimage-golden-abc");
        assertThat(n.getReceiptId()).isNull();                       // absent in this payload
        assertThat(n.getPaidAt()).isEqualTo(Instant.parse("2026-05-25T12:00:00Z")); // paid_at
        // customer_pubkey is accepted-and-ignored (ignoreUnknown) — no exception.
        assertThat(n.getProviderEventId()).isEqualTo("preimage-golden-abc");
    }

    @Test
    void mint_dto_also_accepts_camelCase_aliases() throws Exception {
        // @JsonAlias keeps mint-originated camelCase producers (existing tests,
        // legacy callers) working alongside the adapter's snake_case.
        String camel = "{\"quoteId\":\"q-camel\",\"paymentMethod\":\"cash\",\"amount\":5,"
                + "\"receiptId\":\"r-1\",\"paidAt\":\"2026-05-25T12:00:00Z\"}";
        PaymentNotification n = mapper.readValue(camel, PaymentNotification.class);

        assertThat(n.getQuoteId()).isEqualTo("q-camel");
        assertThat(n.getPaymentMethod()).isEqualTo("cash");
        assertThat(n.getReceiptId()).isEqualTo("r-1");
        assertThat(n.getProviderEventId()).isEqualTo("r-1"); // no preimage → receiptId
    }

    private static String hmacBase64(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body));
    }
}
