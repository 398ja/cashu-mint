package xyz.tcheeric.cashu.mint.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import xyz.tcheeric.cashu.mint.proto.ports.WebhookEvent.Outcome;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for PaymentWebhookController.
 */
@ExtendWith(MockitoExtension.class)
class PaymentWebhookControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private QuoteStatusUpdater quoteStatusUpdater;

    @Mock
    private WebhookSignatureValidator signatureValidator;

    @BeforeEach
    void setUp() {
        PaymentWebhookController controller = new PaymentWebhookController(
                quoteStatusUpdater, signatureValidator);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Test
    void handlePaymentWebhook_shouldReturn200OnSuccess() throws Exception {
        // Given
        when(signatureValidator.validate(any(), any(), any())).thenReturn(true);
        when(quoteStatusUpdater.record(any())).thenReturn(WebhookOutcome.accepted());

        PaymentNotification notification = createNotification("quote123", 1000, "preimage456");
        String json = objectMapper.writeValueAsString(notification);

        // When/Then
        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("Payment recorded"));

        verify(quoteStatusUpdater).record(any());
    }

    @Test
    void handlePaymentWebhook_shouldReturn200OnDuplicate() throws Exception {
        // Given
        when(signatureValidator.validate(any(), any(), any())).thenReturn(true);
        when(quoteStatusUpdater.record(any())).thenReturn(WebhookOutcome.of(Outcome.duplicate));

        PaymentNotification notification = createNotification("quote123", 1000, "preimage456");
        String json = objectMapper.writeValueAsString(notification);

        // When/Then
        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("Duplicate ignored"));
    }

    @Test
    void handlePaymentWebhook_shouldReturn401OnInvalidSignature() throws Exception {
        // Given
        when(signatureValidator.validate(any(), any(), any())).thenReturn(false);

        PaymentNotification notification = createNotification("quote123", 1000, "preimage456");
        String json = objectMapper.writeValueAsString(notification);

        // When/Then
        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Signature", "invalid")
                        .content(json))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Invalid signature"));

        verify(quoteStatusUpdater, never()).record(any());
    }

    @Test
    void handlePaymentWebhook_shouldReturn500OnError() throws Exception {
        // Given
        when(signatureValidator.validate(any(), any(), any())).thenReturn(true);
        when(quoteStatusUpdater.record(any())).thenThrow(new RuntimeException("DB error"));

        PaymentNotification notification = createNotification("quote123", 1000, "preimage456");
        String json = objectMapper.writeValueAsString(notification);

        // When/Then
        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    void handlePaymentWebhook_shouldReturn400OnEmptyBody() throws Exception {
        // Spec 008 review (#331): a missing/empty body is a client mistake
        // (400), not an auth failure (401) — and the signature validator is
        // never consulted.
        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new byte[0]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing notification payload"));

        verify(signatureValidator, never()).validate(any(), any());
        verify(quoteStatusUpdater, never()).record(any());
    }

    @Test
    void health_shouldReturnStatus() throws Exception {
        // Given
        when(signatureValidator.isEnabled()).thenReturn(true);
        when(quoteStatusUpdater.getCacheSize()).thenReturn(5);
        when(quoteStatusUpdater.getProcessedCount()).thenReturn(10);

        // When/Then
        mockMvc.perform(get("/webhook/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.signatureValidationEnabled").value(true))
                .andExpect(jsonPath("$.cachedQuotes").value(5))
                .andExpect(jsonPath("$.processedNotifications").value(10));
    }

    @Test
    void handlePaymentWebhook_shouldAcceptIdempotencyKeyHeader() throws Exception {
        // Given
        when(signatureValidator.validate(any(), any(), any())).thenReturn(true);
        when(quoteStatusUpdater.record(any())).thenReturn(WebhookOutcome.accepted());

        PaymentNotification notification = createNotification("quote123", 1000, "preimage456");
        String json = objectMapper.writeValueAsString(notification);

        // When/Then
        mockMvc.perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "bolt11:quote123")
                        .content(json))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------
    // Spec 008 — end-to-end with the REAL signature validator (not stubbed):
    // proves the controller reads the raw body, HMACs it, and accepts a real
    // adapter-shaped snake_case payload signed over those exact bytes.
    // ---------------------------------------------------------------

    private static final String REAL_SECRET = "it-shared-secret";
    private static final String ADAPTER_BODY =
            "{\"quote_id\":\"q-real-1\",\"payment_method\":\"bolt11\",\"amount\":1000,"
            + "\"unit\":\"sat\",\"preimage\":\"pre-real\",\"customer_pubkey\":\"02ab\","
            + "\"paid_at\":\"2026-05-25T12:00:00Z\"}";

    private MockMvc realValidatorMvc() {
        WebhookProperties props = new WebhookProperties();
        props.setSharedSecret(REAL_SECRET);
        PaymentWebhookController controller = new PaymentWebhookController(
                quoteStatusUpdater, new WebhookSignatureValidator(props));
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void realValidator_acceptsAdapterSignedSnakeCaseBody() throws Exception {
        when(quoteStatusUpdater.record(any())).thenReturn(WebhookOutcome.accepted());
        byte[] body = ADAPTER_BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sig = hmacBase64(body, REAL_SECRET);

        realValidatorMvc().perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Signature", sig)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Payment recorded"));

        // The deserialised notification carried the adapter's snake_case fields.
        org.mockito.ArgumentCaptor<PaymentNotification> captor =
                org.mockito.ArgumentCaptor.forClass(PaymentNotification.class);
        verify(quoteStatusUpdater).record(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getQuoteId()).isEqualTo("q-real-1");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getUnit()).isEqualTo("sat");
    }

    @Test
    void realValidator_rejectsTamperedBodyWith401() throws Exception {
        byte[] body = ADAPTER_BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sig = hmacBase64(body, REAL_SECRET);
        byte[] tampered = ADAPTER_BODY.replace("1000", "9999")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        realValidatorMvc().perform(post("/webhook/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Webhook-Signature", sig)
                        .content(tampered))
                .andExpect(status().isUnauthorized());

        verify(quoteStatusUpdater, never()).record(any());
    }

    private static String hmacBase64(byte[] body, String secret) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.Base64.getEncoder().encodeToString(mac.doFinal(body));
    }

    private PaymentNotification createNotification(String quoteId, int amount, String preimage) {
        return PaymentNotification.builder()
                .quoteId(quoteId)
                .paymentMethod("bolt11")
                .amount(amount)
                .preimage(preimage)
                .paidAt(Instant.now())
                .build();
    }
}
